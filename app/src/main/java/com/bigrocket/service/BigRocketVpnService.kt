package com.bigrocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.HevConfig
import studio.cluvex.aether.core.HevTunnel

/**
 * Core VPN service.
 *
 * It brings up the TUN interface and hands it to [TunPacketRouter], which forwards
 * packets over whichever physical path (Wi-Fi or Cellular) is currently best, while
 * continuously updating each path's weight based on measured latency.
 */
class BigRocketVpnService : VpnService(), NetworkMonitor.NetworkStateListener {

    companion object {
        const val ACTION_STOP = "com.bigrocket.service.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bigrocket_vpn_channel"
        private const val WEIGHT_UPDATE_INTERVAL_MS = 1000L
        // Refreshed every weight-update cycle (well under this), so it never actually expires
        // during normal operation - this is only a safety net against a stuck/leaked lock.
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
        // Upper bound on the one-time pre-flight quality check below - both probes run in
        // parallel, so the realistic worst case is one probe's own ~1.5s timeout; this is a
        // hard ceiling so a pathological case can never delay startup past what's acceptable.
        private const val INITIAL_ASSESSMENT_TIMEOUT_MS = 3000L
        // A single missed latency probe is a transient keep-alive failure, not a lost path.
        private const val PROBE_FAILURES_TO_DECLARE_LOST = 3
        private const val PROBE_SUCCESSES_TO_DECLARE_RECOVERED = 2
        // A path that has carried real payload bytes this recently cannot be genuinely dead -
        // a probe timeout on it means the probe queued behind real traffic, not path loss. Set
        // just over one weight-update cycle plus one probe timeout so it covers exactly the
        // window where the probe could plausibly lose the race against a saturating transfer.
        private const val RECENT_TRAFFIC_GRACE_MS = 3000L
        // hev-socks5-tunnel's OWN internal netif MTU (see HevConfig) - independent of the
        // real VpnService.Builder MTU (1400, set in setupVpn()) that the actual TUN device
        // uses; kept equal to it so hev doesn't fragment/reassemble against a mismatched
        // value for no reason.
        private const val HEV_MTU = 1400
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetRouter: TunPacketRouter? = null
    // Local SOCKS5 upstream for Aether: Aether's external transport is always
    // dialed through BigRocket's weighted physical-path bonding first.
    private var bondingUpstream: BondingSocksServer? = null
    private val path3Router = Path3Router()
    private var networkMonitor: NetworkMonitor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isRunning = false

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null

    // SupervisorJob (not plain Job()): see the identical reasoning in
    // TcpRelayEngine.kt/UdpRelayEngine.kt/TunPacketRouter.kt - this scope now only
    // hosts the weight-update loop, but a plain Job here has the same failure-cascades
    // risk for anything else launched on it in the future.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var weightUpdateJob: Job? = null
    // Last-known probe result per path, so the weight-update loop below can detect a
    // soft failure (ACTIVE/DEGRADED -> DISCONNECTED while Android still reports the network
    // as up) and evict pinned sessions immediately - see TunPacketRouter.notifySoftFailure.
    // See PathHealth.kt for why this is a 3-state machine (ACTIVE/DEGRADED/DISCONNECTED)
    // fed by several independent signals, not a single latency-driven boolean.
    private val wifiHealth = PathHealthTracker(
        failuresToDisconnect = PROBE_FAILURES_TO_DECLARE_LOST,
        successesToRecover = PROBE_SUCCESSES_TO_DECLARE_RECOVERED,
        activityGraceMs = RECENT_TRAFFIC_GRACE_MS
    )
    private val cellularHealth = PathHealthTracker(
        failuresToDisconnect = PROBE_FAILURES_TO_DECLARE_LOST,
        successesToRecover = PROBE_SUCCESSES_TO_DECLARE_RECOVERED,
        activityGraceMs = RECENT_TRAFFIC_GRACE_MS
    )
    // Which packet engine currently owns the TUN fd: the existing JVM router
    // (TunPacketRouter, handles both direct/NONE bonding and JVM-relayed AETHER
    // upstream), or the native hev-socks5-tunnel bridge (studio.cluvex.aether.core.
    // HevTunnel), which - EXPLORATORY, see applyUpstreamMode's comment - replaces the
    // JVM router entirely while active. Only one may ever read the TUN fd at a time.
    @Volatile private var usingHevEngine = false
    // Serializes engine handoff against itself (trafficReady can emit again before a
    // previous handoff finishes) - NOT against controlMutex/start-stop, which already
    // serialize the coroutine this runs on.
    private val engineSwitchMutex = Mutex()
    // Serializes start/stop so a stop arriving while a start is still in progress (or vice
    // versa) can't race on the fields above.
    private val controlMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.log("VpnService", "onCreate()")
        createNotificationChannel()

        // Apply the active embedded proxy's readiness to the router the moment it changes,
        // instead of only picking it up on the next 4s weight-update tick - so app traffic
        serviceScope.launch {
            EmbeddedAetherRuntime.trafficReady.collectLatest { ready -> applyUpstreamMode(ready) }
        }
    }

    /**
     * EXPLORATORY (per explicit request - keep only if it measurably helps stability,
     * otherwise revert to always using packetRouter/TunPacketRouter for AETHER mode too):
     * when Aether's local SOCKS5 is ready, hand the TUN fd to hev-socks5-tunnel (native,
     * mature TCP/IP stack - see HevTunnel.kt/TProxyService.kt for the full history)
     * instead of relaying it through TunPacketRouter/TcpRelayEngine/UdpRelayEngine in the
     * JVM. This ONLY applies to the AETHER upstream path: hev forwards everything to ONE
     * SOCKS5 target, so it has no equivalent for direct/NONE dual-path bonding - that mode
     * is untouched and still always uses TunPacketRouter.
     *
     * KNOWN HANDOFF CAVEAT: TunPacketRouter's read loop is stopped via job cancellation,
     * which cannot preempt an in-flight blocking read() on the TUN fd. In the (rare, sub-
     * millisecond-window) case where a packet arrives during the exact handoff instant, it
     * is possible for one packet to be read by the engine that's on its way out rather than
     * the one taking over. Worst case is one dropped/misrouted packet during a mode switch,
     * not a stability issue in steady state - but it means this is not yet a fully "clean"
     * handoff, and is one of the first things to double-check if this path shows odd
     * one-off glitches right at connect/mode-switch time.
     */
    private suspend fun applyUpstreamMode(aetherReady: Boolean) = engineSwitchMutex.withLock {
        val wantHev = aetherReady && HevTunnel.isAvailable()
        if (wantHev == usingHevEngine) {
            // Same engine as before: for the JVM router this still needs the mode flag
            // applied (AETHER when ready but hev unavailable, e.g. native bridge failed
            // to load - see TProxyService.available - falls back to JVM-relayed AETHER
            // exactly like before this feature existed; NONE otherwise).
            if (!usingHevEngine) {
                packetRouter?.setUpstreamMode(if (aetherReady) UpstreamMode.AETHER else UpstreamMode.NONE)
            }
            return@withLock
        }
        val iface = vpnInterface ?: return@withLock

        if (wantHev) {
            DiagnosticsLog.i("tunnel", "Switching TUN engine: JVM router -> native hev-socks5-tunnel")
            packetRouter?.stop()
            packetRouter = null
            val configPath = HevConfig.write(applicationContext, HEV_MTU)
            try {
                HevTunnel.start(configPath, iface.fd)
                lastHevTotalBytes = 0L
                usingHevEngine = true
            } catch (e: Exception) {
                // hev refused to start (bad config/fd) - fall back to the JVM router with
                // direct routing rather than leaving the tunnel with NO packet engine at all.
                DiagnosticsLog.e("tunnel", "hev-socks5-tunnel failed to start, falling back to JVM router: ${e.message}")
                packetRouter = TunPacketRouter(iface, this, path3Router).also {
                    it.updateNetworks(wifiNetwork, cellularNetwork)
                    it.start()
                }
                usingHevEngine = false
            }
        } else {
            DiagnosticsLog.i("tunnel", "Switching TUN engine: native hev-socks5-tunnel -> JVM router")
            HevTunnel.stop()
            usingHevEngine = false
            packetRouter = TunPacketRouter(iface, this, path3Router).also {
                it.updateNetworks(wifiNetwork, cellularNetwork)
                it.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This must be the very first thing that happens for every start, including a stop
        // request - Android starts its "did you call startForeground()" timer the moment
        // startForegroundService() was invoked, regardless of which action we were sent, and
        // nothing after this call is allowed to block the main thread for long or the system
        // can kill us with "did not then call Service.startForeground()".
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            AppLogger.logError("VpnService", "startForegroundCompat() failed, stopping self", e)
            stopSelf()
            return START_NOT_STICKY
        }
        AppLogger.log("VpnService", "onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            serviceScope.launch { controlMutex.withLock { stopVpn() } }
            return START_NOT_STICKY
        }

        if (!isRunning) {
            serviceScope.launch {
                controlMutex.withLock {
                    if (isRunning) return@withLock // a concurrent call already started us
                    setupVpn()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Once the screen turns off or the app backgrounds, Android puts the Wi-Fi radio into
     * power-save mode (sleeping between beacon intervals) and can let the CPU suspend entirely.
     * Neither is a real network problem, but both inflate our own latency measurements and can
     * pause our relay/weight-update coroutines - which this app was previously misreading as an
     * actual path loss. Holding these for the service's lifetime keeps the radio responsive and
     * the CPU from suspending, so background behavior matches foreground behavior. This is the
     * standard, well-established approach for always-on VPN/calling/streaming-class apps.
     */
    private fun acquireRadioLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "BigRocket:wifiLock")?.apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        } catch (_: Exception) {
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BigRocket:wakeLock")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MS) }
        } catch (_: Exception) {
        }
    }

    private fun releaseRadioLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private suspend fun setupVpn() {
        try {
            acquireRadioLocks()

            // Start observing physical networks before creating the VPN interface. Once the
            // TUN exists, Android may expose it as the active/default network and an event-only
            // monitor can otherwise miss the physical transports during the handoff.
            networkMonitor?.stopMonitoring()
            networkMonitor = NetworkMonitor(applicationContext, this).also { it.startMonitoring() }

            DynamicWeightCalculator.configureUserScores(
                wifiScore = NetworkPreferenceStore.wifiScore(applicationContext),
                cellularScore = NetworkPreferenceStore.cellularScore(applicationContext)
            )

            // This Service instance can be reused across a stop -> start cycle (Android may not
            // have destroyed it yet), so hysteresis/activity state from a previous session must
            // be cleared here - otherwise a path that is perfectly fine in this new session can
            // start out already tagged not-ok (or vice versa) purely from stale leftover state.
            wifiHealth.reset()
            cellularHealth.reset()
            PathActivityMonitor.clear()

            BondingStatus.reset()

            val builder = Builder()
                .setSession("BigRocketVPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1400)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
                // Package could not be excluded from the tunnel; continue without it
            }

            val establishedInterface = builder.establish()
            if (establishedInterface == null) {
                AppLogger.log("VpnService", "builder.establish() returned null - VPN permission revoked or another VPN took over")
                stopVpn()
                return
            }

            vpnInterface = establishedInterface
            isRunning = true

            packetRouter = TunPacketRouter(establishedInterface, this, path3Router).also {
                it.updateWeights(50, 50)
                it.start()
                AppLogger.log("Path3", "Direct output -> Path3")
            }

            // This listener is the physical-network boundary for Aether. Aether
            // connects to it as SOCKS5 upstream; the listener then pins each
            // relay to Wi-Fi/Cellular using the same bonding weights as the
            // direct BigRocket path. Aether is therefore downstream of the
            // BigRocket bonded transport, never a separate physical path.
            bondingUpstream = BondingSocksServer(this, path3Router).also {
                it.updateNetworks(wifiNetwork, cellularNetwork)
                // Seeded with the engine's already-computed score-based weights (set two
                // lines above by configureUserScores(), before any latency probing), not a
                // hardcoded 50/50: BondingSocksServer pins Aether's single tunnel association
                // to one path for its whole session the moment its first packet arrives, which
                // can race startWeightUpdates()'s first probe cycle. A hardcoded 50/50 at that
                // moment forces a tie, and a tie must never be settled by chance - see
                // pickBestNetwork().
                DynamicWeightCalculator.currentWeights().let { w -> it.updateWeights(w.wifiWeight, w.cellularWeight) }
                it.setUpstreamMode(UpstreamMode.NONE)
                it.start()
                AppLogger.log("Path3", "Aether input <- Path3 SOCKS")
            }

            // The monitor may have discovered the physical transports before establish().
            // Apply that snapshot now that the router/TUN actually exists.
            onNetworksUpdated(wifiNetwork, cellularNetwork)

            // Only now is BigRocket actually ready to carry traffic (TUN established, router
            // running, underlying networks applied). Publishing this earlier let consumers such
            // as MainActivity's "start Aether once BigRocket is active" logic fire while setup
            // (in particular the pre-flight quality check above, which takes longer with two
            // bonded paths) was still in flight, racing Aether's own connection attempt against
            // a not-yet-stable tunnel.
            BondingStatus.publish(BondingSnapshot(isServiceActive = true, mode = BondingMode.IDLE))

            TrafficStats.reset()
            startWeightUpdates()
        } catch (e: Exception) {
            AppLogger.logError("VpnService", "setupVpn() failed, tearing down", e)
            stopVpn()
        }
    }

    // NetworkMonitor.NetworkStateListener
    override fun onNetworksUpdated(wifi: Network?, cellular: Network?) {
        val oldWifi = wifiNetwork
        val oldCellular = cellularNetwork
        wifiNetwork = wifi
        cellularNetwork = cellular
        path3Router.updateNetworks(wifi, cellular)

        if (wifi == null) wifiHealth.update(null, LatencyTester.FAILURE)
        if (cellular == null) cellularHealth.update(null, LatencyTester.FAILURE)

        packetRouter?.updateNetworks(wifi, cellular)
        bondingUpstream?.updateNetworks(wifi, cellular)

        val bothAvailable = wifi != null && cellular != null
        val pathRecovered =
            (oldWifi == null && wifi != null) ||
                (oldCellular == null && cellular != null) ||
                (oldWifi != null && wifi != null && oldWifi != wifi) ||
                (oldCellular != null && cellular != null && oldCellular != cellular)
        AppLogger.log(
            "Networks",
            "onNetworksUpdated oldWifi=${oldWifi?.hashCode()} wifi=${wifi?.hashCode()} oldCellular=${oldCellular?.hashCode()} cellular=${cellular?.hashCode()} bothAvailable=$bothAvailable pathRecovered=$pathRecovered",
        )

        when {
            wifi == null && cellular == null -> {
                DynamicWeightCalculator.clear()
                path3Router.updateWeights(0, 0)
                packetRouter?.updateWeights(0, 0)
                bondingUpstream?.updateWeights(0, 0)
            }
            !bothAvailable -> {
                DynamicWeightCalculator.update(
                    wifiAvailable = wifi != null,
                    wifiLatency = 1,
                    cellularAvailable = cellular != null,
                    cellularLatency = 1
                ).also {
                    path3Router.updateWeights(it.wifiWeight, it.cellularWeight)
                    packetRouter?.updateWeights(it.wifiWeight, it.cellularWeight)
                    bondingUpstream?.updateWeights(it.wifiWeight, it.cellularWeight)
                }
            }
            pathRecovered -> {
                val reset = DynamicWeightCalculator.resetForPathRecovery(
                    recoveredWifi = oldWifi == null || (wifi != null && oldWifi != wifi)
                )
                path3Router.updateWeights(reset.wifiWeight, reset.cellularWeight)
                packetRouter?.updateWeights(reset.wifiWeight, reset.cellularWeight)
                bondingUpstream?.updateWeights(reset.wifiWeight, reset.cellularWeight)
            }
        }

        // Tell the OS which physical networks the VPN traffic may use
        if (vpnInterface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val underlying = listOfNotNull(wifi, cellular)
            setUnderlyingNetworks(if (underlying.isNotEmpty()) underlying.toTypedArray() else null)
        }
    }

    // Cumulative hev byte counter last seen, so the loop below can feed TrafficStats
    // (which only understands incremental counts) a delta each cycle - see the comment
    // where it's used.
    @Volatile private var lastHevTotalBytes = 0L

    private fun startWeightUpdates() {
        weightUpdateJob?.cancel()
        weightUpdateJob = serviceScope.launch {
            while (isActive && isRunning) {
                refreshWakeLock()

                // While hev owns the TUN, TcpRelayEngine/UdpRelayEngine (the only other
                // thing that calls TrafficStats.recordBytes) aren't running, so the app's
                // own speed display would otherwise be stuck at 0 the whole time hev is
                // active even though it's genuinely moving traffic. Feed it hev's own
                // counters instead, as a delta since TrafficStats only understands
                // increments.
                if (usingHevEngine) {
                    val traffic = HevTunnel.traffic()
                    if (traffic != null) {
                        val total = traffic.downloadBytes + traffic.uploadBytes
                        val delta = (total - lastHevTotalBytes).coerceAtLeast(0L)
                        lastHevTotalBytes = total
                        if (delta > 0) TrafficStats.recordBytes(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                }

                val wifi = wifiNetwork
                val cellular = cellularNetwork

                // Probed concurrently: sequential probing meant a timed-out path (up to
                // timeoutMs) fully blocked the other path's measurement on the same cycle,
                // which in turn delayed the failure-hysteresis counters below and slowed
                // down exactly the failover this loop exists to drive.
                val (wifiLatency, cellularLatency) = coroutineScope {
                    val wifiDeferred = wifi?.let {
                        async(Dispatchers.IO) { LatencyTester.testLatency(this@BigRocketVpnService, it) }
                    }
                    val cellularDeferred = cellular?.let {
                        async(Dispatchers.IO) { LatencyTester.testLatency(this@BigRocketVpnService, it) }
                    }
                    Pair(
                        wifiDeferred?.await() ?: LatencyTester.FAILURE,
                        cellularDeferred?.await() ?: LatencyTester.FAILURE
                    )
                }

                // A path only counts as unavailable when the probe genuinely fails (timeout,
                // refused, etc.) - a slow-but-successful probe (e.g. Wi-Fi waking from radio
                // power-save right after the screen turns off) still counts as available and
                // is instead handled by DynamicWeightCalculator's inverse-latency scoring,
                // which naturally lowers (but doesn't zero out) that path's share. Previously a
                // hard 900ms cutoff treated "slow" the same as "gone", causing an abrupt full
                // cutover to the other path even though the "lost" one was still usable.
                // A latency probe is a keep-alive signal, not an authoritative Android network
                // disconnect event. Mobile radios, Wi-Fi power-save and transient upstream loss
                // can make one 1.5s probe miss even though the Network is still usable. Treating
                // that single miss as "disconnected" causes the exact UI flap: disconnected ->
                // immediately connected on the next successful probe. Use hysteresis: several
                // consecutive failures are required to declare a path lost, and several successes
                // are required to declare a previously lost path recovered.
                val previousWifiState = wifiHealth.state
                val previousCellularState = cellularHealth.state

                val wifiState = wifiHealth.update(wifi, wifiLatency)
                val cellularState = cellularHealth.update(cellular, cellularLatency)

                val previousWifiOk = previousWifiState != PathHealthState.DISCONNECTED
                val previousCellularOk = previousCellularState != PathHealthState.DISCONNECTED
                val wifiOk = wifiState != PathHealthState.DISCONNECTED
                val cellularOk = cellularState != PathHealthState.DISCONNECTED

                // Only evict pinned sessions after the keep-alive hysteresis confirms a real
                // soft failure. A single missed probe never moves sessions or changes UI state.
                if (previousWifiOk && !wifiOk && wifi != null) {
                    cellular?.let {
                        packetRouter?.notifySoftFailure(wifi, it)
                        bondingUpstream?.notifySoftFailure(wifi)
                    }
                }
                if (previousCellularOk && !cellularOk && cellular != null) {
                    wifi?.let {
                        packetRouter?.notifySoftFailure(cellular, it)
                        bondingUpstream?.notifySoftFailure(cellular)
                    }
                }

                val pathRecoveredAfterProbe =
                    (!previousWifiOk && wifiOk) ||
                        (!previousCellularOk && cellularOk)

                val effectiveWifiLatency = if (wifiLatency == LatencyTester.FAILURE) wifiHealth.lastGoodLatencyMs else wifiLatency
                val effectiveCellularLatency = if (cellularLatency == LatencyTester.FAILURE) cellularHealth.lastGoodLatencyMs else cellularLatency

                if (pathRecoveredAfterProbe && wifiOk && cellularOk) {
                    val reset = DynamicWeightCalculator.resetForPathRecovery(
                        recoveredWifi = (!previousWifiOk && wifiOk)
                    )
                    packetRouter?.updateWeights(reset.wifiWeight, reset.cellularWeight)
                    bondingUpstream?.updateWeights(reset.wifiWeight, reset.cellularWeight)
                }

                val weights = DynamicWeightCalculator.update(
                    wifiAvailable = wifiOk,
                    wifiLatency = effectiveWifiLatency.coerceAtLeast(1),
                    cellularAvailable = cellularOk,
                    cellularLatency = effectiveCellularLatency.coerceAtLeast(1)
                )

                path3Router.updateWeights(weights.wifiWeight, weights.cellularWeight)
                packetRouter?.updateWeights(weights.wifiWeight, weights.cellularWeight)
                bondingUpstream?.updateWeights(weights.wifiWeight, weights.cellularWeight)
                packetRouter?.setUpstreamMode(
                    when {
                        EmbeddedAetherRuntime.isTrafficReady() -> UpstreamMode.AETHER
                        else -> UpstreamMode.NONE
                    }
                )

                val mode = when {
                    wifiOk && cellularOk -> BondingMode.BONDING_ACTIVE
                    wifiOk || cellularOk -> BondingMode.SINGLE_PATH
                    else -> BondingMode.IDLE
                }
                val measuredMbps = Math.round(TrafficStats.sampleThroughputMbps() * 10) / 10.0
                val path3Connected = wifiOk || cellularOk
                val path3LatencyMs = path3Router.latencyMs(
                    wifiLatencyMs = if (wifiOk) effectiveWifiLatency else 0,
                    cellularLatencyMs = if (cellularOk) effectiveCellularLatency else 0
                )
                AppLogger.log(
                    "Path3",
                    "status=${if (path3Connected) "CONNECTED" else "DISCONNECTED"} latency=${path3LatencyMs}ms " +
                        "wifi=${if (wifiOk) effectiveWifiLatency else "off"} cellular=${if (cellularOk) effectiveCellularLatency else "off"}"
                )

                BondingStatus.publish(
                    BondingSnapshot(
                        isServiceActive = true,
                        isWifiConnected = wifiOk,
                        isCellularConnected = cellularOk,
                        wifiLatencyMs = if (wifiOk) effectiveWifiLatency else 0,
                        cellularLatencyMs = if (cellularOk) effectiveCellularLatency else 0,
                        wifiWeight = if (wifiOk) weights.wifiWeight else 0,
                        cellularWeight = if (cellularOk) weights.cellularWeight else 0,
                        mode = mode,
                        bondedSpeedMbps = measuredMbps,
                        path3LatencyMs = path3LatencyMs,
                        path3Connected = path3Connected
                    )
                )

                delay(WEIGHT_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BigRocket",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BigRocket فعال است")
            .setContentText("مسیر هوشمند Wi-Fi و دیتای همراه در حال اجراست")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun stopVpn() {
        AppLogger.log("VpnService", "stopVpn()")
        isRunning = false

        weightUpdateJob?.cancel()
        weightUpdateJob = null

        networkMonitor?.stopMonitoring()
        networkMonitor = null

        if (usingHevEngine) {
            HevTunnel.stop()
            usingHevEngine = false
        }
        packetRouter?.stop()
        packetRouter = null

        bondingUpstream?.stop()
        bondingUpstream = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        releaseRadioLocks()

        wifiNetwork = null
        cellularNetwork = null
        wifiHealth.reset()
        cellularHealth.reset()
        PathActivityMonitor.clear()
        DynamicWeightCalculator.clear()
        BondingStatus.reset()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Called by the system - not our own app - when the VPN is torn down from outside this
     * service (the user disconnects it from Android's own VPN settings screen, or another app
     * takes over as the active VPN). Without handling this, our internal state (isRunning,
     * wake/wifi locks, BondingStatus) could stay stuck as "connected" even though the system has
     * already revoked the tunnel, leaving things out of sync until the process restarts.
     */
    override fun onRevoke() {
        serviceScope.launch { controlMutex.withLock { stopVpn() } }
        super.onRevoke()
    }
}

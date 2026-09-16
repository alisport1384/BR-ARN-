package com.bigrocket.service

import android.net.Network
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class TunPacketRouter(
    private val vpnInterface: ParcelFileDescriptor,
    private val vpnService: BigRocketVpnService,
    private val path3Router: Path3Router
) {

    // SupervisorJob: see the identical comment in TcpRelayEngine.kt. routerJob (the
    // single TUN-read loop) also lives under this scope, so without SupervisorJob a
    // single uncaught exception anywhere under this scope would silently kill packet
    // reading entirely, not just one flow.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var routerJob: Job? = null
    @Volatile private var isRunning = false

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null

    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50
    // Randomized starting phase, not 0, so the round-robin's early proportions aren't
    // deterministically biased the same way every single session.
    private val packetCounter = AtomicInteger(kotlin.random.Random.nextInt(100))

    private val sessionTracker = NetworkSessionTracker()
    private val udpRelayEngine = UdpRelayEngine(vpnService)
    private val tcpRelayEngine = TcpRelayEngine(vpnService)

    @Volatile private var tunOutputStream: FileOutputStream? = null

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        val oldWifi = this.wifiNetwork
        val oldCellular = this.cellularNetwork

        this.wifiNetwork = wifi
        this.cellularNetwork = cellular
        path3Router.updateNetworks(wifi, cellular)

        if (oldWifi != null && wifi == null) {
            notifyNetworkLost(oldWifi)
            if (cellular != null) sessionTracker.migrateSessionsFromLostNetwork(oldWifi, cellular)
        }

        if (oldCellular != null && cellular == null) {
            notifyNetworkLost(oldCellular)
            if (wifi != null) sessionTracker.migrateSessionsFromLostNetwork(oldCellular, wifi)
        }
    }

    /**
     * A network that's truly gone (Android's own onLost) can never be recovered for sessions
     * already bound to it - real sockets can't be silently re-bound to a different network.
     * Without this, an in-flight connection would just go silent and the client app would have
     * to wait out its own TCP retransmission/keepalive timeout (often tens of seconds) before
     * retrying, which is what "feels like a dropped connection" after a brief signal blip.
     */
    private fun notifyNetworkLost(deadNetwork: Network) {
        val outputStream = tunOutputStream ?: return
        tcpRelayEngine.handleNetworkLost(deadNetwork, outputStream)
        udpRelayEngine.handleNetworkLost(deadNetwork)
    }

    /**
     * Android's onLost only fires when a network interface truly goes away (radio off,
     * out of range, ...). It does NOT fire for a network that stays formally "available"
     * but has actually stopped passing traffic (bad AP, upstream carrier/DNS outage,
     * captive portal, severe packet loss). BigRocketVpnService's periodic latency probe
     * is what actually detects that case (a probe that goes from succeeding to timing
     * out on an otherwise still-connected network) - this is its hook to react to it the
     * same way as a real onLost: evict pinned sessions immediately instead of leaving
     * them to silently fail until each one's own idle timeout, and steer future sessions
     * to the surviving path via the same migration NetworkSessionTracker already uses
     * for hard losses. Call this on the ok -> not-ok transition only, not on every failed
     * probe, so a still-genuinely-lost network (already handled by updateNetworks) isn't
     * evicted twice, and so recovered-then-flaky paths aren't churned every cycle.
     */
    fun notifySoftFailure(failedNetwork: Network, fallbackNetwork: Network?) {
        notifyNetworkLost(failedNetwork)
        if (fallbackNetwork != null) {
            sessionTracker.migrateSessionsFromLostNetwork(failedNetwork, fallbackNetwork)
        }
    }

    fun updateWeights(wifiW: Int, cellularW: Int) {
        val changed = this.wifiWeight != wifiW || this.cellularWeight != cellularW
        this.wifiWeight = wifiW
        this.cellularWeight = cellularW
        path3Router.updateWeights(wifiW, cellularW)
        if (changed) packetCounter.set(kotlin.random.Random.nextInt(100))
    }

    fun setUpstreamMode(mode: UpstreamMode) {
        tcpRelayEngine.setUpstreamMode(mode)
        udpRelayEngine.setUpstreamMode(mode)
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        routerJob = scope.launch {
            val inputStream = FileInputStream(vpnInterface.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
            tunOutputStream = outputStream
            val buffer = ByteBuffer.allocate(32767)

            while (isRunning) {
                try {
                    val readBytes = inputStream.read(buffer.array())
                    if (readBytes > 0) {
                        buffer.limit(readBytes)

                        val packet = IpPacketParser.parse(buffer, readBytes)
                        if (packet != null) {
                            processAndRoutePacket(packet, buffer, readBytes, outputStream)
                        }
                    }
                } catch (_: Exception) {
                    // Transient error on the TUN interface; keep reading
                } finally {
                    buffer.clear()
                }
            }
        }
    }

    private fun processAndRoutePacket(
        packet: ParsedIpPacket,
        buffer: ByteBuffer,
        length: Int,
        outputStream: FileOutputStream
    ) {
        val targetNetwork = sessionTracker.getOrAssignNetwork(packet) {
            selectNetworkForPacket()
        } ?: return

        when (packet.protocol) {
            IpProtocol.UDP -> {
                udpRelayEngine.forwardUdpPacket(
                    packet = packet,
                    buffer = buffer,
                    length = length,
                    targetNetwork = targetNetwork,
                    tunOutputStream = outputStream
                )
            }
            IpProtocol.TCP -> {
                tcpRelayEngine.forwardTcpPacket(
                    packet = packet,
                    buffer = buffer,
                    length = length,
                    targetNetwork = targetNetwork,
                    tunOutputStream = outputStream
                )
            }
            else -> {
                // Unsupported protocol (e.g. ICMP); ignored
            }
        }
    }

    private fun selectNetworkForPacket(): Network? = path3Router.selectNetwork(packetCounter.getAndIncrement())

    fun stop() {
        isRunning = false
        sessionTracker.clear()
        udpRelayEngine.clear()
        tcpRelayEngine.clear()
        routerJob?.cancel()
        tunOutputStream = null
    }
}

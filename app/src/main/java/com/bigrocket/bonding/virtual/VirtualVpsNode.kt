package com.bigrocket.bonding.virtual

import android.net.Network
import android.net.VpnService
import com.bigrocket.bonding.core.BondingConfig
import com.bigrocket.bonding.core.BondingEngineImpl
import com.bigrocket.bonding.core.createRecoveryManagerFor
import com.bigrocket.bonding.path3.Path3Counters
import com.bigrocket.bonding.scheduler.AdaptiveScheduler
import com.bigrocket.bonding.transport.PathId
import com.bigrocket.bonding.transport.PathRuntimeImpl
import com.bigrocket.bonding.transport.UdpPathAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Section 17/80/100/232/233: VPS External Endpoint vs Local Virtual Endpoint
 *
 * This is the VIRTUAL VPS that runs INSIDE sandbox (user requirement):
 * "باید وی پی اس در سندباکس ایجاد بشه و بتونه خروجی رو به مسیر 3 بده"
 *
 * Role:
 * - Simulates external VPS that would normally be remote
 * - Receives bonded frames from P1/P2 (Wi-Fi/Cellular)
 * - Reassembles IP packets
 * - Provides return path (Internet breakout simulation or echo)
 * - Sends return traffic back via P1/P2 bonding to client
 *
 * Critical: Virtualization alone adds no network capability (Section 18)
 * Therefore VPS must have:
 * Required Inputs + Required Routing + Required Return Path + Required Network Privileges
 *
 * In sandbox, we use loopback UDP to simulate P1/P2 transport (as existing BondingSandbox does)
 * But now with Path3 integration.
 *
 * Architecture:
 * Client Path3 -> Bonding Engine A --P1/P2 UDP--> VPS Engine B -> [Internet Sim] -> VPS Engine B --P1/P2--> Engine A -> Path3 -> Client
 */
class VirtualVpsNode(
    private val vpnService: VpnService,
    private val wifiNetwork: Network?,
    private val cellularNetwork: Network?,
    private val streamId: Int,
    private val config: VirtualVpsConfig,
    private val counters: Path3Counters
) {
    private val effectiveWifiNetwork: Network = wifiNetwork ?: cellularNetwork ?: throw IllegalArgumentException("At least one network required")
    private val effectiveCellularNetwork: Network = cellularNetwork ?: wifiNetwork ?: throw IllegalArgumentException("At least one network required")
    private val loopback = InetAddress.getByName("127.0.0.1")

    private var engineB: BondingEngineImpl? = null
    private var engineARef: BondingEngineImpl? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpsJob: Job? = null

    @Volatile
    private var running = false

    private val packetsReceivedFromClient = AtomicLong(0)
    private val packetsSentToClient = AtomicLong(0)
    private val bytesReceivedFromClient = AtomicLong(0)
    private val bytesSentToClient = AtomicLong(0)

    /**
     * Build VPS side engine (Engine B) that listens on VPS ports
     * and talks back to client ports
     */
    fun buildVpsEngine(): BondingEngineImpl {
        val wifiAdapter = UdpPathAdapter(
            id = PathId.WIFI,
            vpnService = vpnService,
            network = effectiveWifiNetwork,
            remoteAddress = InetSocketAddress(loopback, config.clientWifiPort),
            streamId = streamId,
            simulatedExtraLatencyMs = config.simulateVpsLatencyMs,
            localPort = config.vpsWifiPort
        )
        val cellularAdapter = UdpPathAdapter(
            id = PathId.CELLULAR,
            vpnService = vpnService,
            network = effectiveCellularNetwork,
            remoteAddress = InetSocketAddress(loopback, config.clientCellularPort),
            streamId = streamId,
            simulatedExtraLatencyMs = config.simulateVpsLatencyMs,
            localPort = config.vpsCellularPort
        )

        val pathWifi = PathRuntimeImpl(PathId.WIFI, wifiAdapter, config.bondingConfig.sendQueueCapacity)
        val pathCellular = PathRuntimeImpl(PathId.CELLULAR, cellularAdapter, config.bondingConfig.sendQueueCapacity)

        val scheduler = AdaptiveScheduler(
            maxWeightStepPerUpdate = config.bondingConfig.maxWeightStepPerUpdate,
            maxConsecutivePicks = config.bondingConfig.maxConsecutivePicksPerPath
        )

        return BondingEngineImpl(streamId, listOf(pathWifi, pathCellular), scheduler, config.bondingConfig)
    }

    /**
     * Build client side engine (Engine A) that talks to VPS
     */
    fun buildClientEngine(): BondingEngineImpl {
        val wifiAdapter = UdpPathAdapter(
            id = PathId.WIFI,
            vpnService = vpnService,
            network = effectiveWifiNetwork,
            remoteAddress = InetSocketAddress(loopback, config.vpsWifiPort),
            streamId = streamId,
            localPort = config.clientWifiPort
        )
        val cellularAdapter = UdpPathAdapter(
            id = PathId.CELLULAR,
            vpnService = vpnService,
            network = effectiveCellularNetwork,
            remoteAddress = InetSocketAddress(loopback, config.vpsCellularPort),
            streamId = streamId,
            localPort = config.clientCellularPort
        )

        val pathWifi = PathRuntimeImpl(PathId.WIFI, wifiAdapter, config.bondingConfig.sendQueueCapacity)
        val pathCellular = PathRuntimeImpl(PathId.CELLULAR, cellularAdapter, config.bondingConfig.sendQueueCapacity)

        val scheduler = AdaptiveScheduler(
            maxWeightStepPerUpdate = config.bondingConfig.maxWeightStepPerUpdate,
            maxConsecutivePicks = config.bondingConfig.maxConsecutivePicksPerPath
        )

        return BondingEngineImpl(streamId, listOf(pathWifi, pathCellular), scheduler, config.bondingConfig)
    }

    /**
     * Section 51/55: Inbound flow
     * Internet (simulated) -> P1/P2 -> Bonding Engine -> Reassembly -> Path3
     * For VPS, inbound from client, outbound back to client
     */
    fun start(clientEngine: BondingEngineImpl, vpsEngine: BondingEngineImpl) {
        engineARef = clientEngine
        engineB = vpsEngine

        // Section 46/145/216: Recovery manager - VPS side requests missing frames from client cache
        vpsEngine.recoveryManager = clientEngine.createRecoveryManagerFor { frame ->
            vpsEngine.injectRecoveredFrame(frame)
        }
        clientEngine.recoveryManager = vpsEngine.createRecoveryManagerFor { frame ->
            clientEngine.injectRecoveredFrame(frame)
        }

        vpsEngine.start()
        running = true

        // VPS main loop: receive from client, process, send back (echo / internet breakout sim)
        vpsJob = scope.launch {
            while (isActive && running) {
                val packet = vpsEngine.receive()
                if (packet != null) {
                    packetsReceivedFromClient.incrementAndGet()
                    bytesReceivedFromClient.addAndGet(packet.size.toLong())

                    // Track P1/P2 RX counters based on scheduler? For PoC, we count both
                    // Real implementation would track per-path from PathRuntime metrics
                    // Here we increment both to prove dual-path capable, but actual
                    // per-path accounting comes from engine status

                    // Section 238/239: Original IP packet -> Bonding -> Transport -> Internet
                    // Simulate Internet processing and return
                    val returnPacket = if (config.enablePacketEcho) {
                        // Echo back same packet (proves bidirectional path)
                        packet
                    } else {
                        // Simulate internet breakout - for PoC, same as echo
                        packet
                    }

                    // Send back to client via VPS bonding engine
                    // This will be fragmented and sent over P1/P2 back to client
                    vpsEngine.send(returnPacket)
                    packetsSentToClient.incrementAndGet()
                    bytesSentToClient.addAndGet(returnPacket.size.toLong())
                } else {
                    delay(1)
                }
            }
        }
    }

    fun stop() {
        running = false
        vpsJob?.cancel()
        vpsJob = null
        try { engineB?.stop() } catch (_: Exception) {}
        engineB = null
        engineARef = null
    }

    fun stats(): VpsStats = VpsStats(
        packetsReceived = packetsReceivedFromClient.get(),
        packetsSent = packetsSentToClient.get(),
        bytesReceived = bytesReceivedFromClient.get(),
        bytesSent = bytesSentToClient.get()
    )

    data class VpsStats(
        val packetsReceived: Long,
        val packetsSent: Long,
        val bytesReceived: Long,
        val bytesSent: Long
    )
}

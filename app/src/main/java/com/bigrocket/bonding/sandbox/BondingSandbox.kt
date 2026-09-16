package com.bigrocket.bonding.sandbox

import android.net.Network
import android.net.VpnService
import com.bigrocket.bonding.api.BondingStatus
import com.bigrocket.bonding.core.BondingConfig
import com.bigrocket.bonding.core.BondingEngineImpl
import com.bigrocket.bonding.core.createRecoveryManagerFor
import com.bigrocket.bonding.scheduler.AdaptiveScheduler
import com.bigrocket.bonding.transport.PathId
import com.bigrocket.bonding.transport.PathRuntimeImpl
import com.bigrocket.bonding.transport.UdpPathAdapter
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * The runnable in-app sandbox: bonds Path 1 (Wi-Fi) and Path 2 (Cellular) into Path 3 - a single,
 * ordered, verified output stream - entirely inside the app, with no external server.
 *
 * Builds two complete [BondingEngineImpl]s on real sockets bound to the given [Network]s (Section
 * 309): "Engine A" plays the sending side (the Splitter - fragments the test payload and
 * dispatches it across both paths) and "Engine B" plays the destination side (the Joiner -
 * receives from both paths and reassembles). Both run in this process because no remote endpoint
 * that speaks this frame protocol exists yet (spec Section 208/295-297) - see [UdpPathAdapter]'s
 * class doc for exactly what that does and doesn't prove about real network behavior, and pass
 * [simulateWifiExtraLatencyMs]/[simulateCellularExtraLatencyMs]/loss ratios to exercise the
 * reorder/recovery machinery the way a real Wi-Fi/Cellular RTT gap would (Section 484-487).
 *
 * This is intentionally additive and self-contained (spec Section 259/273: "Integration Safety
 * Rules... ممنوع: تغییر Network Core موجود") - it does not touch BigRocketVpnService,
 * TcpRelayEngine, or BondingSocksServer, and nothing calls it automatically; it is meant to be
 * invoked on demand (e.g. from a debug screen) to validate the bonding engine itself.
 */
object BondingSandbox {

    suspend fun run(
        vpnService: VpnService,
        wifiNetwork: Network,
        cellularNetwork: Network,
        payload: ByteArray = defaultTestPayload(),
        config: BondingConfig = BondingConfig(),
        simulateWifiExtraLatencyMs: Long = 0,
        simulateCellularExtraLatencyMs: Long = 0,
        simulateWifiLossRatio: Double = 0.0,
        simulateCellularLossRatio: Double = 0.0,
        overallTimeoutMs: Long = 8_000,
    ): BondingSandboxReport {
        val streamId = Random.nextInt()
        val notes = mutableListOf<String>()

        val engineB = buildEngine(
            streamId = streamId,
            vpnService = vpnService,
            wifiNetwork = wifiNetwork,
            cellularNetwork = cellularNetwork,
            localWifiPort = PORT_B_WIFI,
            localCellularPort = PORT_B_CELLULAR,
            remoteWifiPort = PORT_A_WIFI,
            remoteCellularPort = PORT_A_CELLULAR,
            config = config,
        )

        val engineA = buildEngine(
            streamId = streamId,
            vpnService = vpnService,
            wifiNetwork = wifiNetwork,
            cellularNetwork = cellularNetwork,
            localWifiPort = PORT_A_WIFI,
            localCellularPort = PORT_A_CELLULAR,
            remoteWifiPort = PORT_B_WIFI,
            remoteCellularPort = PORT_B_CELLULAR,
            config = config,
            wifiExtraLatencyMs = simulateWifiExtraLatencyMs,
            cellularExtraLatencyMs = simulateCellularExtraLatencyMs,
            wifiLossRatio = simulateWifiLossRatio,
            cellularLossRatio = simulateCellularLossRatio,
        )

        // Section 46/145/216: Engine B is the side that discovers gaps (it's the Joiner), so it
        // is the one that needs a RecoveryManager - built against Engine A's own cache of
        // recently sent frames (see createRecoveryManagerFor's doc for the same-process caveat).
        engineB.recoveryManager = engineA.createRecoveryManagerFor(onResend = { frame ->
            engineB.injectRecoveredFrame(frame)
        })

        try {
            engineB.start()
            engineA.start()
        } catch (e: Exception) {
            notes.add("Failed to start engines: ${e.message}")
            runCatching { engineA.stop() }
            runCatching { engineB.stop() }
            return BondingSandboxReport(
                success = false,
                bytesSent = 0,
                bytesReceived = 0,
                integrityMatch = false,
                elapsedMs = 0,
                senderStatus = null,
                receiverStatus = null,
                notes = notes,
            )
        }

        val startedAt = now()
        engineA.send(payload)

        val received = ByteArrayOutputStream()
        while (received.size() < payload.size && (now() - startedAt) < overallTimeoutMs) {
            val chunk = engineB.receive()
            if (chunk != null) {
                received.write(chunk)
            } else {
                delay(POLL_INTERVAL_MS)
            }
        }
        val elapsed = now() - startedAt

        if (received.size() < payload.size) {
            notes.add(
                "Timed out after ${overallTimeoutMs}ms with ${received.size()}/${payload.size} bytes delivered " +
                    "(likely permanently-lost frames past the reorder window's hard timeout - see receiverStatus.framesPermanentlyLost).",
            )
        }

        val receivedBytes = received.toByteArray()
        val integrityMatch = receivedBytes.size == payload.size && receivedBytes.contentEquals(payload)
        if (!integrityMatch && received.size() == payload.size) {
            notes.add("Byte count matched but content differed - possible checksum/reorder bug, not just loss.")
        }

        val senderStatus = engineA.status()
        val receiverStatus = engineB.status()

        engineA.stop()
        engineB.stop()

        return BondingSandboxReport(
            success = integrityMatch,
            bytesSent = payload.size,
            bytesReceived = receivedBytes.size,
            integrityMatch = integrityMatch,
            elapsedMs = elapsed,
            senderStatus = senderStatus,
            receiverStatus = receiverStatus,
            notes = notes,
        )
    }

    private fun buildEngine(
        streamId: Int,
        vpnService: VpnService,
        wifiNetwork: Network,
        cellularNetwork: Network,
        localWifiPort: Int,
        localCellularPort: Int,
        remoteWifiPort: Int,
        remoteCellularPort: Int,
        config: BondingConfig,
        wifiExtraLatencyMs: Long = 0,
        cellularExtraLatencyMs: Long = 0,
        wifiLossRatio: Double = 0.0,
        cellularLossRatio: Double = 0.0,
    ): BondingEngineImpl {
        val loopback = java.net.InetAddress.getByName(LOOPBACK_ADDRESS)

        val wifiAdapter = UdpPathAdapter(
            id = PathId.WIFI,
            vpnService = vpnService,
            network = wifiNetwork,
            remoteAddress = InetSocketAddress(loopback, remoteWifiPort),
            streamId = streamId,
            simulatedExtraLatencyMs = wifiExtraLatencyMs,
            simulatedLossRatio = wifiLossRatio,
            localPort = localWifiPort,
        )
        val cellularAdapter = UdpPathAdapter(
            id = PathId.CELLULAR,
            vpnService = vpnService,
            network = cellularNetwork,
            remoteAddress = InetSocketAddress(loopback, remoteCellularPort),
            streamId = streamId,
            simulatedExtraLatencyMs = cellularExtraLatencyMs,
            simulatedLossRatio = cellularLossRatio,
            localPort = localCellularPort,
        )

        val pathWifi = PathRuntimeImpl(PathId.WIFI, wifiAdapter, config.sendQueueCapacity)
        val pathCellular = PathRuntimeImpl(PathId.CELLULAR, cellularAdapter, config.sendQueueCapacity)

        val scheduler = AdaptiveScheduler(
            maxWeightStepPerUpdate = config.maxWeightStepPerUpdate,
            maxConsecutivePicks = config.maxConsecutivePicksPerPath,
        )

        return BondingEngineImpl(streamId, listOf(pathWifi, pathCellular), scheduler, config)
    }

    private fun defaultTestPayload(): ByteArray {
        // Deterministic pseudo-random content (not all-zero/repeating) so a byte-order or
        // off-by-one reassembly bug can't hide behind coincidentally-identical bytes.
        val random = Random(DEFAULT_PAYLOAD_SEED)
        return ByteArray(DEFAULT_PAYLOAD_SIZE) { random.nextInt(256).toByte() }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private const val LOOPBACK_ADDRESS = "127.0.0.1"
    private const val PORT_A_WIFI = 47_100
    private const val PORT_A_CELLULAR = 47_101
    private const val PORT_B_WIFI = 47_200
    private const val PORT_B_CELLULAR = 47_201
    private const val POLL_INTERVAL_MS = 10L
    private const val DEFAULT_PAYLOAD_SIZE = 512 * 1024
    private const val DEFAULT_PAYLOAD_SEED = 0xB0DE12L
}

data class BondingSandboxReport(
    val success: Boolean,
    val bytesSent: Int,
    val bytesReceived: Int,
    val integrityMatch: Boolean,
    val elapsedMs: Long,
    val senderStatus: BondingStatus?,
    val receiverStatus: BondingStatus?,
    val notes: List<String>,
) {
    /** Human-readable, e.g. for a debug screen or logcat - not part of the report's data
     *  contract, just a convenience. */
    fun summary(): String = buildString {
        appendLine(if (success) "PASS" else "FAIL")
        appendLine("bytes: $bytesReceived / $bytesSent (${elapsedMs}ms)")
        senderStatus?.paths?.forEach { p ->
            appendLine("  sender  ${p.id}: ${p.state} weight=${p.weightPercent}% latency=${p.metrics.latencyMs}ms loss=${p.metrics.packetLoss}")
        }
        receiverStatus?.let { s ->
            appendLine("  recovered=${s.recoveryFulfilled}/${s.recoveryRequested} permanentlyLost=${s.framesPermanentlyLost}")
        }
        notes.forEach { appendLine("  note: $it") }
    }
}

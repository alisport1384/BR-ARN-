package com.bigrocket.bonding.path3

import com.bigrocket.bonding.core.BondingConfig
import com.bigrocket.bonding.core.BondingEngineImpl
import com.bigrocket.bonding.transport.PathId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Section 92/149/151/152: Path3 Adapter - connects Android Network Stack <-> Bonding Engine
 *
 * Responsibility:
 * - Packet RX/TX between Path3Endpoint and BondingEngine
 * - Lifecycle management
 * - No bonding logic duplication (Section 92)
 *
 * This is Layer B in Three-Layer Execution Model (Section 91):
 * Layer A: Android / Network Stack
 * Layer B: Path3 Adapter (this class)
 * Layer C: Bonding Engine + P1/P2
 *
 * Ensures:
 * - Single logical egress (Section 96)
 * - No direct bypass (Section 95)
 * - Bounded queues (Section 148)
 * - Failure containment (Section 123)
 */
class Path3Adapter(
    private val path3Endpoint: Path3Endpoint,
    private val bondingEngine: BondingEngineImpl,
    private val config: BondingConfig = BondingConfig(),
    private val counters: Path3Counters = path3Endpoint.counters
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var txJob: Job? = null // Android -> Bonding
    private var rxJob: Job? = null // Bonding -> Android

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true

        // Section 214: Outbound - Android -> TUN read -> Bonding Engine -> P1/P2
        txJob = scope.launch {
            while (isActive && running) {
                val packet = path3Endpoint.receiveFromAndroid()
                if (packet != null) {
                    try {
                        // Section 52/240: Packet enters bonding with sequencing
                        bondingEngine.send(packet)
                        counters.incBondedTx()
                    } catch (_: Exception) {
                        // Section 260: Malformed packet -> reject, session remains
                    }
                } else {
                    delay(1)
                }
            }
        }

        // Section 214: Inbound - P1/P2 -> Bonding -> TUN write -> Android
        rxJob = scope.launch {
            while (isActive && running) {
                val reassembled = bondingEngine.receive()
                if (reassembled != null) {
                    val delivered = path3Endpoint.sendToAndroid(reassembled)
                    if (delivered) {
                        counters.incBondedRx()
                    }
                } else {
                    delay(1)
                }
            }
        }
    }

    fun stop() {
        running = false
        txJob?.cancel()
        rxJob?.cancel()
        txJob = null
        rxJob = null
    }

    fun isRunning(): Boolean = running
}

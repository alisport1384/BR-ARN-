package com.bigrocket.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BondingMode {
    IDLE,
    SINGLE_PATH,
    BONDING_ACTIVE
}

data class BondingSnapshot(
    val isServiceActive: Boolean = false,
    val isWifiConnected: Boolean = false,
    val isCellularConnected: Boolean = false,
    val wifiLatencyMs: Long = 0,
    val cellularLatencyMs: Long = 0,
    val wifiWeight: Int = 0,
    val cellularWeight: Int = 0,
    val mode: BondingMode = BondingMode.IDLE,
    val bondedSpeedMbps: Double = 0.0,
    val path3LatencyMs: Long = 0,
    val path3Connected: Boolean = false
)

/**
 * Single source of truth for live bonding state. [BigRocketVpnService] is the only component
 * that actually measures latency and drives real routing weights, so it is the sole writer here;
 * the UI (MainActivity) is a pure observer.
 */
object BondingStatus {
    private val _state = MutableStateFlow(BondingSnapshot())
    val state: StateFlow<BondingSnapshot> = _state.asStateFlow()

    // Bounded history of recent throughput samples for the lightweight UI graph.
    // Small and fixed-size on purpose - this is read/rendered only when a new point is added,
    // never on a redraw loop, so it stays cheap regardless of how long the VPN has been running.
    const val HISTORY_CAPACITY = 30
    private val _throughputHistory = MutableStateFlow<List<Double>>(emptyList())
    val throughputHistory: StateFlow<List<Double>> = _throughputHistory.asStateFlow()

    fun publish(snapshot: BondingSnapshot) {
        _state.value = snapshot
        val updated = (_throughputHistory.value + snapshot.bondedSpeedMbps).takeLast(HISTORY_CAPACITY)
        _throughputHistory.value = updated
    }

    fun reset() {
        _state.value = BondingSnapshot()
        _throughputHistory.value = emptyList()
    }
}

package com.bigrocket.service

import android.net.Network

/**
 * The single logical Path 3 output of BigRocket.
 *
 * Path 1/2 are physical transports; callers above this class only ask Path 3
 * for the physical Network to use for a new flow. Aether's SOCKS input and
 * BigRocket's direct router both use this same selector.
 */
class Path3Router {
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        wifiNetwork = wifi
        cellularNetwork = cellular
    }

    fun updateWeights(wifi: Int, cellular: Int) {
        wifiWeight = wifi.coerceIn(0, 100)
        cellularWeight = cellular.coerceIn(0, 100)
        AppLogger.log("Path3", "updateWeights wifi=$wifiWeight cellular=$cellularWeight")
    }

    fun selectNetwork(slot: Int? = null): Network? {
        val wifi = wifiNetwork
        val cellular = cellularNetwork
        if (wifi != null && cellular != null) {
            if (slot != null) {
                val normalized = Math.floorMod(slot, 100)
                return if (normalized < wifiWeight) wifi else cellular
            }
            return when {
                wifiWeight > cellularWeight -> wifi
                cellularWeight > wifiWeight -> cellular
                else -> wifi
            }
        }
        return wifi ?: cellular
    }

    fun latencyMs(wifiLatencyMs: Long, cellularLatencyMs: Long): Long {
        val wifi = wifiNetwork != null && wifiWeight > 0
        val cellular = cellularNetwork != null && cellularWeight > 0
        return when {
            wifi && cellular -> {
                val total = wifiWeight + cellularWeight
                ((wifiLatencyMs.coerceAtLeast(0) * wifiWeight) +
                    (cellularLatencyMs.coerceAtLeast(0) * cellularWeight)) /
                    total.coerceAtLeast(1)
            }
            wifi -> wifiLatencyMs.coerceAtLeast(0)
            cellular -> cellularLatencyMs.coerceAtLeast(0)
            else -> 0L
        }
    }

    /** For diagnostics/logging only - identifies whether [network] is the current Wi-Fi or
     *  Cellular reference, or neither (e.g. already stale/replaced). */
    fun describeNetwork(network: Network?): String = when (network) {
        null -> "none"
        wifiNetwork -> "wifi"
        cellularNetwork -> "cellular"
        else -> "unknown(${network})"
    }
}

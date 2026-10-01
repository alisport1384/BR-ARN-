package com.bigrocket.service

import android.net.Network

/**
 * The single logical Path 3 output of BigRocket.
 *
 * Path 1/2 are physical transports; callers above this class only ask Path 3
 * for the physical Network to use for a new flow. Aether's SOCKS input and
 * BigRocket's direct router both use this same selector.
 */
class Path3Router : PathSelector {
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50

    // selectNetwork() is called per-packet/per-flow (can be thousands of times a second) -
    // logging every call would flood the log and add real I/O cost. Logging only the flips
    // (which physical path a new selection actually lands on) gives the same verification
    // value - "is Path 3 actually alternating/favoring the path I expect" - at negligible cost.
    @Volatile private var lastSelectedWasWifi: Boolean? = null

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        wifiNetwork = wifi
        cellularNetwork = cellular
        AppLogger.log("Path3", "updateNetworks wifi=$wifi cellular=$cellular")
    }

    /** Read-only snapshot for external observers (e.g. the Virtual Bonding sandbox UI). */
    fun currentWifiNetwork(): Network? = wifiNetwork
    fun currentCellularNetwork(): Network? = cellularNetwork

    fun updateWeights(wifi: Int, cellular: Int) {
        wifiWeight = wifi.coerceIn(0, 100)
        cellularWeight = cellular.coerceIn(0, 100)
        AppLogger.log("Path3", "updateWeights wifi=$wifiWeight cellular=$cellularWeight")
    }

    override fun selectNetwork(slot: Int?): Network? {
        val wifi = wifiNetwork
        val cellular = cellularNetwork
        val selected = if (wifi != null && cellular != null) {
            if (slot != null) {
                val normalized = Math.floorMod(slot, 100)
                if (normalized < wifiWeight) wifi else cellular
            } else {
                when {
                    wifiWeight > cellularWeight -> wifi
                    cellularWeight > wifiWeight -> cellular
                    else -> wifi
                }
            }
        } else {
            wifi ?: cellular
        }
        logIfFlipped(selected, wifi)
        return selected
    }

    private fun logIfFlipped(selected: Network?, wifi: Network?) {
        val nowWifi = selected != null && selected == wifi
        if (selected != null && lastSelectedWasWifi != nowWifi) {
            lastSelectedWasWifi = nowWifi
            AppLogger.log("Path3", "selectNetwork flipped to ${if (nowWifi) "wifi" else "cellular"} (wifi=$wifiWeight cellular=$cellularWeight)")
        }
    }

    /**
     * Returns all available networks ordered by weight descending (best first).
     * Used for fallback when the primary network fails to connect - ensures
     * registration traffic (api.cloudflareclient.com) can still succeed via the
     * other path instead of failing outright.
     *
     * FIX for WireGuard/Gool SOCKS5 readiness: previously only pickBestNetwork()
     * was tried; if that network resolved api.cloudflareclient.com to IPv6 that
     * was unreachable, the whole registration failed and PortProbe timed out
     * with "socks5 listener did not become ready". Now we try all networks.
     */
    fun allNetworksSorted(): List<Network> {
        val wifi = wifiNetwork
        val cellular = cellularNetwork
        return when {
            wifi != null && cellular != null -> {
                if (wifiWeight >= cellularWeight) listOf(wifi, cellular)
                else listOf(cellular, wifi)
            }
            wifi != null -> listOf(wifi)
            cellular != null -> listOf(cellular)
            else -> emptyList()
        }
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

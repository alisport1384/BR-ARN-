package com.bigrocket.service

/**
 * Routing share assigned to the two physical network paths.
 * The two values are expected to sum to 100 when at least one path is available.
 */
data class NetworkWeights(
    val wifiWeight: Int,
    val cellularWeight: Int
)

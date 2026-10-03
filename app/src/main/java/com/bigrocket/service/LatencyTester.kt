package com.bigrocket.service

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object LatencyTester {

    /** Sentinel returned when the probe genuinely fails (timeout, refused, DNS failure, etc.) -
     *  distinct from any real elapsed-time value, unlike the old 999L which could collide with
     *  a real (if slow) successful measurement and be misread as a hard failure. */
    const val FAILURE: Long = -1L

    fun testLatency(
        vpnService: BigRocketVpnService,
        network: Network,
        targetHost: String = "1.1.1.1",
        port: Int = 443,
        timeoutMs: Int = 1500
    ): Long {
        val startTime = System.currentTimeMillis()
        return try {
            // Create the socket from the selected Network itself. This avoids consulting
            // ConnectivityManager.activeNetwork, which becomes the VPN once TUN is up.
            val socket = network.socketFactory.createSocket()
            socket.use {
                check(vpnService.protect(it)) { "Unable to protect latency probe from VPN" }
                it.connect(InetSocketAddress(targetHost, port), timeoutMs)
            }
            System.currentTimeMillis() - startTime
        } catch (_: Exception) {
            FAILURE
        }
    }

    /**
     * Fraction of [probeCount] independent TCP connects that fail, run concurrently (not
     * sequentially - probeCount connects back to back would multiply wall time and defeat the
     * point of a "quick" loss check). A single testLatency() call only tells you "did the path
     * work right now"; this tells you how CONSISTENTLY it works, which testLatency() cannot -
     * a path that succeeds 7/10 rapid connects is meaningfully worse than one that succeeds
     * 10/10, even if both show similar latency on the connects that do succeed.
     *
     * Deliberately NOT called every weight-update tick (see BigRocketVpnService's separate,
     * much slower loss-probe loop) - probeCount connects per call is real socket/battery/data
     * cost, cheap once every ~20s, not something to pay every 1s.
     */
    suspend fun testLossRate(
        vpnService: BigRocketVpnService,
        network: Network,
        targetHost: String = "1.1.1.1",
        port: Int = 443,
        timeoutMs: Int = 800,
        probeCount: Int = 8,
    ): Double = coroutineScope {
        val results = (1..probeCount).map {
            async(Dispatchers.IO) {
                testLatency(vpnService, network, targetHost, port, timeoutMs) != FAILURE
            }
        }.awaitAll()
        val failures = results.count { !it }
        failures.toDouble() / probeCount
    }
}

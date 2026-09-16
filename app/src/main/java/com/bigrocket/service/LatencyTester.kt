package com.bigrocket.service

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket

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
}

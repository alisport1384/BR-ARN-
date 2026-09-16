package com.bigrocket.service

import android.net.Network
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ConnectionPathStats(
    val network: Network,
    @Volatile var rttMs: Long = 80L,
    @Volatile var smoothedJitter: Long = 5L,
    @Volatile var lostPacketsCount: Int = 0,
    @Volatile var totalPacketsSent: Int = 0
) {
    fun getLossRatio(): Float {
        if (totalPacketsSent == 0) return 0f
        return lostPacketsCount.toFloat() / totalPacketsSent.toFloat()
    }
}

object SmartTrafficEngine {

    private val pathStats = ConcurrentHashMap<Network, ConnectionPathStats>()
    private val packetCounter = AtomicInteger(0)

    fun recordRealTrafficMetrics(network: Network, sampleRttMs: Long, isDropped: Boolean) {
        val stats = pathStats.getOrPut(network) { ConnectionPathStats(network) }
        
        stats.totalPacketsSent++
        if (isDropped) {
            stats.lostPacketsCount++
        } else {
            val delta = kotlin.math.abs(sampleRttMs - stats.rttMs)
            stats.smoothedJitter = ((stats.smoothedJitter * 3 + delta) / 4)
            stats.rttMs = ((stats.rttMs * 7 + sampleRttMs) / 8)
        }
    }

    fun calculateAdaptiveWeights(wifi: Network?, cellular: Network?): Pair<Int, Int> {
        if (wifi == null && cellular == null) return Pair(0, 0)
        if (wifi != null && cellular == null) return Pair(100, 0)
        if (wifi == null && cellular != null) return Pair(0, 100)

        val wifiStats = pathStats.getOrPut(wifi!!) { ConnectionPathStats(wifi) }
        val cellStats = pathStats.getOrPut(cellular!!) { ConnectionPathStats(cellular) }

        val wifiScore = computePathScore(wifiStats)
        val cellScore = computePathScore(cellStats)

        val totalScore = wifiScore + cellScore
        if (totalScore <= 0.0) return Pair(50, 50)

        val wifiWeight = ((wifiScore / totalScore) * 100).toInt().coerceIn(10, 90)
        val cellWeight = 100 - wifiWeight

        return Pair(wifiWeight, cellWeight)
    }

    private fun computePathScore(stats: ConnectionPathStats): Double {
        val effectiveRtt = stats.rttMs.coerceAtLeast(5L).toDouble()
        val jitterPenalty = stats.smoothedJitter * 2.0
        val lossPenalty = stats.getLossRatio() * 500.0

        val pathCost = effectiveRtt + jitterPenalty + lossPenalty
        return 100000.0 / pathCost
    }

    fun routePacket(wifi: Network?, cellular: Network?, wifiWeight: Int): Network? {
        if (wifi == null) return cellular
        if (cellular == null) return wifi

        val count = packetCounter.getAndIncrement() % 100
        val absoluteCount = if (count < 0) count + 100 else count

        return if (absoluteCount < wifiWeight) wifi else cellular
    }

    fun clampTcpMssIfNeeded(packetData: ByteArray, length: Int, targetMss: Int = 1360): Boolean {
        if (length < 40) return false

        val versionAndIhl = packetData[0].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return false

        val ipHeaderLen = (versionAndIhl and 0x0F) * 4
        if (length < ipHeaderLen + 20) return false

        val protocol = packetData[9].toInt() and 0xFF
        if (protocol != 6) return false

        val tcpFlags = packetData[ipHeaderLen + 13].toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0

        if (!isSyn) return false

        val tcpDataOffset = ((packetData[ipHeaderLen + 12].toInt() and 0xFF) shr 4) * 4
        if (tcpDataOffset < 20 || length < ipHeaderLen + tcpDataOffset) return false

        var optionOffset = ipHeaderLen + 20
        val optionEnd = ipHeaderLen + tcpDataOffset

        var modified = false

        while (optionOffset < optionEnd) {
            val kind = packetData[optionOffset].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) {
                optionOffset++
                continue
            }

            if (optionOffset + 1 >= optionEnd) break
            val optLen = packetData[optionOffset + 1].toInt() and 0xFF
            if (optLen < 2 || optionOffset + optLen > optionEnd) break

            if (kind == 2 && optLen == 4) {
                val currentMss = ((packetData[optionOffset + 2].toInt() and 0xFF) shl 8) or
                        (packetData[optionOffset + 3].toInt() and 0xFF)

                if (currentMss > targetMss) {
                    packetData[optionOffset + 2] = ((targetMss shr 8) and 0xFF).toByte()
                    packetData[optionOffset + 3] = (targetMss and 0xFF).toByte()
                    modified = true
                }
                break
            }
            optionOffset += optLen
        }

        if (modified) {
            recalculateTcpChecksum(packetData, ipHeaderLen, length)
        }

        return modified
    }

    private fun recalculateTcpChecksum(packetData: ByteArray, ipHeaderLen: Int, length: Int) {
        val tcpLen = length - ipHeaderLen
        packetData[ipHeaderLen + 16] = 0.toByte()
        packetData[ipHeaderLen + 17] = 0.toByte()

        var sum = 0L

        for (i in 12..15 step 2) {
            sum += ((packetData[i].toInt() and 0xFF) shl 8) or (packetData[i + 1].toInt() and 0xFF)
        }
        for (i in 16..19 step 2) {
            sum += ((packetData[i].toInt() and 0xFF) shl 8) or (packetData[i + 1].toInt() and 0xFF)
        }
        sum += 6
        sum += tcpLen

        var i = ipHeaderLen
        val end = length
        while (i < end - 1) {
            val word = ((packetData[i].toInt() and 0xFF) shl 8) or (packetData[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packetData[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = (sum.toInt() xor 0xFFFF) and 0xFFFF
        packetData[ipHeaderLen + 16] = ((checksum shr 8) and 0xFF).toByte()
        packetData[ipHeaderLen + 17] = (checksum and 0xFF).toByte()
    }
}
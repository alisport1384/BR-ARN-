package com.bigrocket.service

import android.net.Network
import java.util.concurrent.ConcurrentHashMap

data class SessionKey(
    val protocol: IpProtocol,
    val sourceIp: String,
    val sourcePort: Int,
    val destinationIp: String,
    val destinationPort: Int
)

data class SessionValue(
    var assignedNetwork: Network,
    var lastActiveTimeMs: Long = System.currentTimeMillis()
)

class NetworkSessionTracker {

    private val activeSessions = ConcurrentHashMap<SessionKey, SessionValue>()

    fun getOrAssignNetwork(
        packet: ParsedIpPacket,
        selectNetworkBlock: () -> Network?
    ): Network? {
        val key = SessionKey(
            protocol = packet.protocol,
            sourceIp = packet.sourceIp,
            sourcePort = packet.sourcePort,
            destinationIp = packet.destinationIp,
            destinationPort = packet.destinationPort
        )

        val currentTime = System.currentTimeMillis()
        val existingSession = activeSessions[key]

        if (existingSession != null) {
            existingSession.lastActiveTimeMs = currentTime
            return existingSession.assignedNetwork
        }

        val targetNetwork = selectNetworkBlock() ?: return null
        activeSessions[key] = SessionValue(assignedNetwork = targetNetwork, lastActiveTimeMs = currentTime)
        
        cleanupOldSessions(currentTime)
        return targetNetwork
    }

    fun migrateSessionsFromLostNetwork(deadNetwork: Network, fallbackNetwork: Network) {
        activeSessions.values.forEach { session ->
            if (session.assignedNetwork == deadNetwork) {
                session.assignedNetwork = fallbackNetwork
            }
        }
    }

    private fun cleanupOldSessions(currentTime: Long) {
        val iterator = activeSessions.entries.iterator()
        val timeoutMs = 60_000L
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((currentTime - entry.value.lastActiveTimeMs) > timeoutMs) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        activeSessions.clear()
    }
}
package com.bigrocket.service

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

// A UDP "session" here is just a local NAT-style mapping so replies can find their way back.
// Real NAT gateways time these out after a period of inactivity (commonly ~30s) - without doing
// the same, every DNS lookup (etc.) leaks one socket forever, eventually exhausting the app's
// file descriptor limit and breaking all relaying until the VPN is manually restarted.
private const val UDP_IDLE_TIMEOUT_MS = 30_000L

// Same reasoning as TcpRelayEngine's cap: bounds worst-case file-descriptor usage under a
// sudden burst of new sessions, independent of the idle timeout above.
private const val MAX_CONCURRENT_UDP_SESSIONS = 200

private class UdpSession(
    val socket: DatagramSocket,
    val network: Network,
    val aetherAssociation: AetherUpstream.UdpAssociation?,
    @Volatile var lastActivityMs: Long
)

class UdpRelayEngine(
    private val vpnService: BigRocketVpnService
) {

    // SupervisorJob: see the identical comment in TcpRelayEngine.kt - one UDP session's
    // uncaught exception must not cancel every other session sharing this scope.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = ConcurrentHashMap<SessionKey, UdpSession>()
    private val socketJobs = ConcurrentHashMap<SessionKey, Job>()
    @Volatile private var upstreamMode = UpstreamMode.NONE

    fun setUpstreamMode(mode: UpstreamMode) {
        if (upstreamMode == mode) return
        upstreamMode = mode
        clear()
    }

    fun forwardUdpPacket(
        packet: ParsedIpPacket,
        buffer: ByteBuffer,
        length: Int,
        targetNetwork: Network,
        tunOutputStream: FileOutputStream
    ) {
        val key = SessionKey(
            protocol = packet.protocol,
            sourceIp = packet.sourceIp,
            sourcePort = packet.sourcePort,
            destinationIp = packet.destinationIp,
            destinationPort = packet.destinationPort
        )

        try {
            var session = activeSessions[key]
            if (session == null || session.socket.isClosed) {
                if (activeSessions.size >= MAX_CONCURRENT_UDP_SESSIONS) {
                    activeSessions.entries.minByOrNull { it.value.lastActivityMs }?.let {
                        closeSession(it.key)
                    }
                }

                val mode = upstreamMode
                val socket = DatagramSocket()
                check(vpnService.protect(socket)) { "Unable to protect UDP socket from VPN" }
                if (mode == UpstreamMode.NONE) targetNetwork.bindSocket(socket)
                // Wake up periodically even with no incoming data so idle sessions get reaped
                // instead of holding their file descriptor open forever.
                socket.soTimeout = UDP_IDLE_TIMEOUT_MS.toInt()

                val aetherAssociation = if (mode == UpstreamMode.AETHER) AetherUpstream.openUdp(vpnService) else null
                session = UdpSession(socket, targetNetwork, aetherAssociation, System.currentTimeMillis())
                activeSessions[key] = session

                val job = scope.launch {
                    listenForResponses(key, session, tunOutputStream)
                }
                socketJobs[key] = job
            }

            session.lastActivityMs = System.currentTimeMillis()

            val payloadSize = packet.dataLength
            if (payloadSize > 0) {
                val payload = ByteArray(payloadSize)
                buffer.position(packet.dataOffset)
                buffer.get(payload, 0, payloadSize)

                if (session.aetherAssociation != null) {
                    session.aetherAssociation.send(packet.destinationIp, packet.destinationPort, payload)
                } else {
                    val destAddr = InetAddress.getByName(packet.destinationIp)
                    val outPacket = DatagramPacket(payload, payloadSize, destAddr, packet.destinationPort)
                    session.socket.send(outPacket)
                    PathActivityMonitor.recordActivity(session.network)
                }
                TrafficStats.recordBytes(payloadSize)
            }
        } catch (e: Exception) {
            AppLogger.logError(
                "Direct-UDP",
                "forward failed ${key.destinationIp}:${key.destinationPort}",
                e
            )
            closeSession(key)
        }
    }

    private fun listenForResponses(
        key: SessionKey,
        session: UdpSession,
        tunOutputStream: FileOutputStream
    ) {
        val rxBuffer = ByteArray(32767)
        val socket = session.socket
        while (!socket.isClosed) {
            try {
                val payload = if (session.aetherAssociation != null) {
                    session.aetherAssociation.receive(rxBuffer)?.payload
                } else {
                    val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
                    socket.receive(rxPacket)
                    PathActivityMonitor.recordActivity(session.network)
                    rxPacket.data.copyOf(rxPacket.length)
                }
                if (payload == null) {
                    if (System.currentTimeMillis() - session.lastActivityMs >= UDP_IDLE_TIMEOUT_MS) break
                    continue
                }
                session.lastActivityMs = System.currentTimeMillis()

                val replyPacketBytes = IpPacketBuilder.buildUdpResponsePacket(
                    srcIp = key.destinationIp,
                    srcPort = key.destinationPort,
                    dstIp = key.sourceIp,
                    dstPort = key.sourcePort,
                    data = payload,
                    dataLength = payload.size
                )

                synchronized(tunOutputStream) {
                    tunOutputStream.write(replyPacketBytes)
                    tunOutputStream.flush()
                }
                TrafficStats.recordBytes(payload.size)
            } catch (_: SocketTimeoutException) {
                // No traffic within the timeout window; close if truly idle, otherwise keep waiting
                // (lastActivityMs may have just been bumped by a fresh outgoing send).
                if (System.currentTimeMillis() - session.lastActivityMs >= UDP_IDLE_TIMEOUT_MS) {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        closeSession(key)
    }

    private fun closeSession(key: SessionKey) {
        socketJobs.remove(key)?.cancel()
        activeSessions.remove(key)?.let {
            try { it.socket.close() } catch (_: Exception) {}
            runCatching { it.aetherAssociation?.close() }
        }
    }

    /**
     * Called when a physical network genuinely goes away. UDP has no teardown handshake to
     * signal the client with, so this just frees the dead-network-bound socket immediately
     * (instead of leaving it to time out over the next 30s) - the client's own next retry
     * naturally opens a fresh session on the surviving network.
     */
    fun handleNetworkLost(deadNetwork: Network) {
        activeSessions.entries.toList().forEach { (key, session) ->
            if (session.network == deadNetwork) {
                closeSession(key)
            }
        }
    }

    fun clear() {
        socketJobs.values.forEach { it.cancel() }
        socketJobs.clear()
        activeSessions.values.forEach {
            try { it.socket.close() } catch (_: Exception) {}
            runCatching { it.aetherAssociation?.close() }
        }
        activeSessions.clear()
    }
}

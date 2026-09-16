package com.bigrocket.service

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// Max app-data bytes per synthetic TCP segment we emit, sized to stay under the VPN
// interface's declared MTU (1400) including the 20-byte IP + 20-byte TCP headers.
private const val MAX_TCP_SEGMENT_PAYLOAD = 1360

// Bound on how many not-yet-written upload chunks can queue up per session before the
// consumer (runSocketWriter) is treated as stuck - see the comment on TcpSession.writeChannel
// for why this exists at all.
private const val TCP_WRITE_QUEUE_CAPACITY = 64

// One pending upload chunk plus the client-sequence value it advances to once actually
// written - carried through the channel so ordering/seq-accounting stays correct even though
// the real write now happens on a different coroutine than the one that received the packet.
private data class PendingWrite(val payload: ByteArray, val clientNextSeqAfter: Long)

// Fallback window to assume before the client has told us its real one, and the cap on how
// long we'll wait for the client's window to open before giving up on a stalled peer.
private const val DEFAULT_CLIENT_WINDOW = 65535
private const val WINDOW_WAIT_STEP_MS = 20L
private const val WINDOW_WAIT_MAX_MS = 5000L

// Defensive idle reaper: a well-behaved flow should always close via FIN/RST, but if any edge
// case ever leaves a session dangling, this guarantees it can't leak forever. Kept fairly short
// (not e.g. minutes) because each session holds a real OS socket/file-descriptor - a browsing
// session can easily open 100-300+ distinct TCP connections within a couple of minutes (ads,
// trackers, images, fonts, each on a different host), and a long idle window means almost none
// of them get reclaimed during that time, eventually exhausting the process's file-descriptor
// limit - which shows up as "some connections keep working, new ones just stop" exactly like
// running low on a shared resource, not a real network problem.
private const val TCP_REAPER_INTERVAL_MS = 20_000L
private const val TCP_IDLE_TIMEOUT_MS = 90_000L

// Hard ceiling on concurrent sessions, independent of the idle timeout above - bounds worst-case
// file-descriptor usage even under a sudden burst of many simultaneous new connections (e.g. a
// speed-test's "finding optimal server" step opening dozens of probes at once). The least
// recently active session is evicted to make room, mirroring standard LRU connection-tracking
// behavior used by real proxies/NAT gateways.
private const val MAX_CONCURRENT_TCP_SESSIONS = 200

private class TcpSession(
    val socket: Socket,
    val network: Network,
    // Next sequence number this engine will use when sending data/flags to the client.
    @Volatile var serverSeq: Long,
    // Next sequence number expected from the client (i.e. the ACK we send).
    @Volatile var clientNextSeq: Long,
    // Highest of our own sequence numbers the client has acknowledged so far.
    @Volatile var clientAckedSeq: Long,
    // Client's last advertised TCP receive window, in bytes.
    @Volatile var clientWindow: Int,
    @Volatile var lastActivityMs: Long = System.currentTimeMillis(),
    // Upload-direction (client -> remote) bytes are hand off here instead of being written to
    // the socket directly on the caller's thread. forwardTcpPacket/forwardDataToSocket run on
    // TunPacketRouter's single TUN-read coroutine, shared by every session on every physical
    // path. Socket.getOutputStream().write() can legitimately block for seconds when the
    // underlying path is failing (send buffer full, no ACKs getting through, e.g. Wi-Fi about
    // to drop) - a blocking write there would stall that one shared coroutine, freezing packet
    // processing for every other session on every path (including the still-healthy one) until
    // it times out. Queuing here and doing the real write on this session's own coroutine
    // (runSocketWriter) confines that risk to one session's own thread.
    val writeChannel: Channel<PendingWrite> = Channel(TCP_WRITE_QUEUE_CAPACITY)
)

/**
 * Relays TCP flows between the local TUN interface and a real Socket connected out over the
 * chosen physical network. Since the app-side OS TCP stack expects a normal three-way handshake
 * on the TUN device, this engine performs a minimal but correct handshake (SYN -> SYN/ACK -> ACK)
 * before relaying payload bytes, keeps sequence/ack numbers consistent in both directions, chunks
 * outgoing data to fit the interface MTU, and paces writes to never exceed the client's
 * advertised receive window.
 *
 * Because the TUN device is a purely local, in-order, lossless link, respecting the client's
 * window is sufficient to prevent stalls - there is no real network loss to recover from, so no
 * retransmission queue is needed as long as we never overrun what the client says it can buffer.
 *
 * This is still not a full TCP implementation (no reordering or congestion control), but it is
 * enough for typical request/response and bulk-download flows such as HTTP(S).
 */
class TcpRelayEngine(
    private val vpnService: BigRocketVpnService
) {

    // SupervisorJob, not a plain Job: this scope is shared by every TCP session's own
    // relay coroutine (openSession launches one per connection). With a plain Job, an
    // uncaught exception in ANY single session's coroutine cancels the whole scope's
    // Job - which cancels every other session's coroutine too (including ones on the
    // still-healthy path), AND leaves the scope permanently cancelled so openSession's
    // future scope.launch{} calls silently no-op forever. That's a full, silent outage
    // requiring a VPN restart to recover, triggered by one bad connection - this is the
    // most likely cause of "one path drops -> the whole network is lost". SupervisorJob
    // makes one session's failure stay contained to that session.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<SessionKey, TcpSession>()
    private val socketJobs = ConcurrentHashMap<SessionKey, Job>()
    private val writerJobs = ConcurrentHashMap<SessionKey, Job>()
    private var reaperJob: Job? = null
    @Volatile private var upstreamMode = UpstreamMode.NONE

    init {
        reaperJob = scope.launch {
            while (true) {
                delay(TCP_REAPER_INTERVAL_MS)
                val now = System.currentTimeMillis()
                sessions.entries.toList().forEach { (key, session) ->
                    if (session.socket.isClosed || now - session.lastActivityMs >= TCP_IDLE_TIMEOUT_MS) {
                        closeSession(key)
                    }
                }
            }
        }
    }

    fun setUpstreamMode(mode: UpstreamMode) {
        if (upstreamMode == mode) return
        upstreamMode = mode
        sessions.values.forEach { runCatching { it.socket.close() } }
        sessions.clear()
        socketJobs.values.forEach { it.cancel() }
        socketJobs.clear()
        writerJobs.values.forEach { it.cancel() }
        writerJobs.clear()
    }

    fun forwardTcpPacket(
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

        val flags = packet.tcpFlags
        val existing = sessions[key]

        if (flags and TcpFlag.RST != 0) {
            closeSession(key)
            return
        }

        if (existing == null) {
            // Only a fresh SYN (without ACK) may open a new session.
            if (flags and TcpFlag.SYN != 0 && flags and TcpFlag.ACK == 0) {
                openSession(key, packet, targetNetwork, tunOutputStream)
            }
            return
        }

        // Every packet from the client tells us how much of our data it has received and how
        // much more it can currently buffer - the response loop uses this to pace itself.
        if (flags and TcpFlag.ACK != 0 && packet.tcpAck > existing.clientAckedSeq) {
            existing.clientAckedSeq = packet.tcpAck
        }
        existing.clientWindow = packet.tcpWindow
        existing.lastActivityMs = System.currentTimeMillis()

        if (packet.dataLength > 0) {
            val payload = ByteArray(packet.dataLength)
            buffer.position(packet.dataOffset)
            buffer.get(payload, 0, packet.dataLength)
            forwardDataToSocket(key, existing, payload, packet, tunOutputStream)
        }

        if (flags and TcpFlag.FIN != 0) {
            existing.clientNextSeq = packet.tcpSeq + packet.dataLength + 1
            sendAck(key, existing, tunOutputStream)
            try {
                existing.socket.shutdownOutput()
            } catch (_: Exception) {
            }
        }
    }

    private fun openSession(
        key: SessionKey,
        synPacket: ParsedIpPacket,
        targetNetwork: Network,
        tunOutputStream: FileOutputStream
    ) {
        scope.launch {
            var socket: Socket? = null
            try {
                evictLruSessionIfAtCapacity(tunOutputStream)

                socket = when (upstreamMode) {
                    UpstreamMode.AETHER -> AetherUpstream.openTcp(
                        vpnService = vpnService,
                        destinationHost = key.destinationIp,
                        destinationPort = key.destinationPort,
                    )
                    UpstreamMode.NONE -> {
                        val direct = targetNetwork.socketFactory.createSocket()
                        check(vpnService.protect(direct)) { "Unable to protect TCP socket from VPN" }
                        direct.connect(InetSocketAddress(key.destinationIp, key.destinationPort), 4000)
                        direct
                    }
                }

                val serverIsn = Random.nextLong(0, 0xFFFFFFFL)
                val session = TcpSession(
                    socket = socket,
                    network = targetNetwork,
                    serverSeq = serverIsn + 1, // SYN consumes one sequence number
                    clientNextSeq = synPacket.tcpSeq + 1,
                    clientAckedSeq = serverIsn + 1,
                    clientWindow = if (synPacket.tcpWindow > 0) synPacket.tcpWindow else DEFAULT_CLIENT_WINDOW
                )
                sessions[key] = session

                // Reply with SYN/ACK to complete the handshake with the local TCP stack.
                val synAck = IpPacketBuilder.buildTcpResponsePacket(
                    srcIp = key.destinationIp,
                    srcPort = key.destinationPort,
                    dstIp = key.sourceIp,
                    dstPort = key.sourcePort,
                    seqNumber = serverIsn,
                    ackNumber = session.clientNextSeq,
                    flags = TcpFlag.SYN or TcpFlag.ACK,
                    data = ByteArray(0),
                    dataLength = 0
                )
                synchronized(tunOutputStream) {
                    tunOutputStream.write(synAck)
                    tunOutputStream.flush()
                }

                val job = launch { listenForTcpResponses(key, session, tunOutputStream) }
                socketJobs[key] = job
                writerJobs[key] = launch { runSocketWriter(key, session, tunOutputStream) }
            } catch (e: Exception) {
                AppLogger.logError(
                    "Direct-TCP",
                    "openSession failed ${key.destinationIp}:${key.destinationPort}",
                    e
                )
                try {
                    val rst = IpPacketBuilder.buildTcpResponsePacket(
                        srcIp = key.destinationIp,
                        srcPort = key.destinationPort,
                        dstIp = key.sourceIp,
                        dstPort = key.sourcePort,
                        seqNumber = 0L,
                        ackNumber = synPacket.tcpSeq + 1,
                        flags = TcpFlag.RST or TcpFlag.ACK,
                        data = ByteArray(0),
                        dataLength = 0
                    )
                    synchronized(tunOutputStream) {
                        tunOutputStream.write(rst)
                        tunOutputStream.flush()
                    }
                } catch (_: Exception) {
                }
                socket?.let { try { it.close() } catch (_: Exception) {} }
                sessions.remove(key)
            }
        }
    }

    private fun forwardDataToSocket(
        key: SessionKey,
        session: TcpSession,
        payload: ByteArray,
        packet: ParsedIpPacket,
        tunOutputStream: FileOutputStream
    ) {
        // Must not call session.socket's blocking write() here: this function runs on
        // TunPacketRouter's single shared TUN-read coroutine (see the comment on
        // TcpSession.writeChannel). Handing the payload to the queue and returning
        // immediately is what keeps one struggling path from freezing every other flow.
        val pending = PendingWrite(payload, packet.tcpSeq + payload.size)
        val result = session.writeChannel.trySend(pending)
        if (result.isFailure) {
            // Queue is full, meaning runSocketWriter's actual write() has been stuck for
            // TCP_WRITE_QUEUE_CAPACITY chunks already - treat that exactly like a hard write
            // failure (below) rather than blocking here waiting for room to free up.
            closeSession(key)
        }
    }

    /**
     * Drains one session's upload queue and performs the real (possibly blocking) socket
     * write on this session's own coroutine, off TunPacketRouter's shared TUN-read thread -
     * see the comment on TcpSession.writeChannel for why this split exists. Sequence
     * accounting and the client ACK are only advanced/sent once a chunk is actually written,
     * preserving the original synchronous behavior's semantics.
     */
    private suspend fun runSocketWriter(key: SessionKey, session: TcpSession, tunOutputStream: FileOutputStream) {
        try {
            val outStream = session.socket.getOutputStream()
            for (pending in session.writeChannel) {
                outStream.write(pending.payload)
                outStream.flush()
                session.clientNextSeq = pending.clientNextSeqAfter
                session.lastActivityMs = System.currentTimeMillis()
                TrafficStats.recordBytes(pending.payload.size)
                if (upstreamMode == UpstreamMode.NONE) PathActivityMonitor.recordActivity(session.network)
                sendAck(key, session, tunOutputStream)
            }
        } catch (_: Exception) {
            // Real write failure (socket dead/reset) - tear the session down like before.
        } finally {
            closeSession(key)
        }
    }

    private fun sendAck(key: SessionKey, session: TcpSession, tunOutputStream: FileOutputStream) {
        try {
            val ackPacket = IpPacketBuilder.buildTcpResponsePacket(
                srcIp = key.destinationIp,
                srcPort = key.destinationPort,
                dstIp = key.sourceIp,
                dstPort = key.sourcePort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientNextSeq,
                flags = TcpFlag.ACK,
                data = ByteArray(0),
                dataLength = 0
            )
            synchronized(tunOutputStream) {
                tunOutputStream.write(ackPacket)
                tunOutputStream.flush()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun listenForTcpResponses(
        key: SessionKey,
        session: TcpSession,
        tunOutputStream: FileOutputStream
    ) {
        val rxBuffer = ByteArray(16384)
        try {
            val inStream: InputStream = session.socket.getInputStream()
            while (!session.socket.isClosed) {
                val readBytes = inStream.read(rxBuffer)
                if (readBytes > 0) {
                    if (!writeChunkedRespectingWindow(key, session, rxBuffer, readBytes, tunOutputStream)) {
                        // The client's window never opened up in time (stalled/dead peer); give up.
                        break
                    }
                } else if (readBytes == -1) {
                    val finPacket = IpPacketBuilder.buildTcpResponsePacket(
                        srcIp = key.destinationIp,
                        srcPort = key.destinationPort,
                        dstIp = key.sourceIp,
                        dstPort = key.sourcePort,
                        seqNumber = session.serverSeq,
                        ackNumber = session.clientNextSeq,
                        flags = TcpFlag.FIN or TcpFlag.ACK,
                        data = ByteArray(0),
                        dataLength = 0
                    )
                    synchronized(tunOutputStream) {
                        tunOutputStream.write(finPacket)
                        tunOutputStream.flush()
                    }
                    session.serverSeq += 1
                    break
                }
            }
        } catch (_: Exception) {
            // Socket closed or errored; tear down the session
        } finally {
            closeSession(key)
        }
    }

    /**
     * Writes [readBytes] from [rxBuffer] to the TUN device as one or more MTU-sized TCP
     * segments, never sending more unacknowledged data than the client's advertised receive
     * window allows. Returns false if the client's window stayed closed past [WINDOW_WAIT_MAX_MS].
     */
    private suspend fun writeChunkedRespectingWindow(
        key: SessionKey,
        session: TcpSession,
        rxBuffer: ByteArray,
        readBytes: Int,
        tunOutputStream: FileOutputStream
    ): Boolean {
        var offset = 0
        while (offset < readBytes) {
            var waitedMs = 0L
            var allowed = (session.clientAckedSeq + session.clientWindow) - session.serverSeq
            while (allowed <= 0) {
                if (waitedMs >= WINDOW_WAIT_MAX_MS) return false
                delay(WINDOW_WAIT_STEP_MS)
                waitedMs += WINDOW_WAIT_STEP_MS
                allowed = (session.clientAckedSeq + session.clientWindow) - session.serverSeq
            }

            val chunkSize = minOf(
                MAX_TCP_SEGMENT_PAYLOAD,
                readBytes - offset,
                allowed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            if (chunkSize <= 0) continue

            val responseBytes = IpPacketBuilder.buildTcpResponsePacket(
                srcIp = key.destinationIp,
                srcPort = key.destinationPort,
                dstIp = key.sourceIp,
                dstPort = key.sourcePort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientNextSeq,
                flags = TcpFlag.PSH or TcpFlag.ACK,
                data = rxBuffer,
                dataLength = chunkSize,
                dataOffset = offset
            )

            synchronized(tunOutputStream) {
                tunOutputStream.write(responseBytes)
                tunOutputStream.flush()
            }

            session.serverSeq += chunkSize
            TrafficStats.recordBytes(chunkSize)
            if (upstreamMode == UpstreamMode.NONE) PathActivityMonitor.recordActivity(session.network)
            session.lastActivityMs = System.currentTimeMillis()
            offset += chunkSize
        }
        return true
    }

    private fun closeSession(key: SessionKey) {
        socketJobs.remove(key)?.cancel()
        writerJobs.remove(key)?.cancel()
        sessions.remove(key)?.let {
            it.writeChannel.close()
            try { it.socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Bounds worst-case file-descriptor usage regardless of idle-timeout tuning: if we're
     * already at the concurrent-session ceiling, make room by closing the single
     * least-recently-active session (with an RST so its client notices immediately and can
     * retry) before opening a new one. A linear scan over at most MAX_CONCURRENT_TCP_SESSIONS
     * entries is negligible - this only runs when opening a brand new session, not per packet.
     */
    private fun evictLruSessionIfAtCapacity(tunOutputStream: FileOutputStream) {
        if (sessions.size < MAX_CONCURRENT_TCP_SESSIONS) return

        val oldest = sessions.entries.minByOrNull { it.value.lastActivityMs } ?: return
        val key = oldest.key
        val session = oldest.value
        try {
            val rst = IpPacketBuilder.buildTcpResponsePacket(
                srcIp = key.destinationIp,
                srcPort = key.destinationPort,
                dstIp = key.sourceIp,
                dstPort = key.sourcePort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientNextSeq,
                flags = TcpFlag.RST or TcpFlag.ACK,
                data = ByteArray(0),
                dataLength = 0
            )
            synchronized(tunOutputStream) {
                tunOutputStream.write(rst)
                tunOutputStream.flush()
            }
        } catch (_: Exception) {
        }
        closeSession(key)
    }

    /**
     * Called when a physical network genuinely goes away (Android's own onLost, not just a
     * momentary latency blip). Any session still pinned to that network can never recover its
     * real backing socket - it was bound to that specific network and can't be silently
     * re-bound - so without this, the client app would just see silence and have to wait out
     * its own TCP retransmission/keepalive timeout (often tens of seconds) before giving up and
     * retrying. Sending an immediate RST lets the client notice right away and retry, which
     * (thanks to session-selection already excluding the dead network) lands on the surviving
     * path within moments instead of the connection appearing to hang.
     */
    fun handleNetworkLost(deadNetwork: Network, tunOutputStream: FileOutputStream) {
        sessions.entries.toList().forEach { (key, session) ->
            if (session.network != deadNetwork) return@forEach
            try {
                val rst = IpPacketBuilder.buildTcpResponsePacket(
                    srcIp = key.destinationIp,
                    srcPort = key.destinationPort,
                    dstIp = key.sourceIp,
                    dstPort = key.sourcePort,
                    seqNumber = session.serverSeq,
                    ackNumber = session.clientNextSeq,
                    flags = TcpFlag.RST or TcpFlag.ACK,
                    data = ByteArray(0),
                    dataLength = 0
                )
                synchronized(tunOutputStream) {
                    tunOutputStream.write(rst)
                    tunOutputStream.flush()
                }
            } catch (_: Exception) {
            }
            closeSession(key)
        }
    }

    fun clear() {
        reaperJob?.cancel()
        socketJobs.values.forEach { it.cancel() }
        socketJobs.clear()
        writerJobs.values.forEach { it.cancel() }
        writerJobs.clear()
        sessions.values.forEach { session ->
            session.writeChannel.close()
            try { session.socket.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }
}

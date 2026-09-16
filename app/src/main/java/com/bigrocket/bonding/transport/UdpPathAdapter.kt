package com.bigrocket.bonding.transport

import android.net.Network
import android.net.VpnService
import com.bigrocket.bonding.frame.BinaryFrameCodec
import com.bigrocket.bonding.frame.Frame
import com.bigrocket.bonding.frame.FrameFlags
import com.bigrocket.bonding.frame.FrameHeader
import com.bigrocket.bonding.frame.MessageType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * Section 300/301 (UDP Compatibility / Internal Transport Recommendation): UDP is used rather
 * than a raw TCP passthrough because TCP's own ordering/ACK/congestion-control would fight this
 * engine's own framing (Section 298) - this adapter is deliberately "dumb" UDP, all
 * ordering/recovery is handled above it by [com.bigrocket.bonding.buffer.FrameReorderBuffer] /
 * [com.bigrocket.bonding.recovery.RecoveryManager].
 *
 * Section 309 (Socket Binding Model): the socket is protected (Section 302/303 - required so a
 * BigRocket VpnService's own TUN doesn't recursively capture this adapter's own traffic) and
 * bound to [network] specifically, so this path's frames genuinely leave over that physical
 * interface rather than whatever Android's default route happens to be - the same fix already
 * applied in BondingSocksServer for exactly this reason.
 *
 * IMPORTANT HONESTY NOTE (do not remove): [network].bindSocket() only affects routing for
 * destinations Android actually routes over a physical interface. If [remoteAddress] is a
 * loopback address (127.0.0.1), the datagram never leaves the device - the kernel routes
 * 127.0.0.0/8 via the loopback interface regardless of which Network the socket is bound to.
 * Binding to [network] in that case still proves the bind/protect call itself succeeds, but does
 * NOT prove the data physically traveled over that radio. [com.bigrocket.bonding.sandbox.BondingSandbox]
 * uses a loopback destination today because no remote endpoint speaks this frame protocol yet
 * (see the specification document, Section 208/295-297) - it validates the bonding engine's own
 * correctness (framing, scheduling proportions, reorder, recovery), not real cross-network
 * throughput aggregation. Point [remoteAddress] at a real, protocol-compatible remote endpoint to
 * get that; this class supports either.
 */
class UdpPathAdapter(
    override val id: PathId,
    private val vpnService: VpnService,
    private val network: Network,
    private val remoteAddress: InetSocketAddress,
    private val streamId: Int,
    /** Section 484-487 (Network Simulation Testing): optional artificial delay/loss so the
     *  sandbox can exercise the reorder/recovery machinery the way a real Wi-Fi/Cellular
     *  latency gap would, without needing two physically different networks with genuinely
     *  different RTTs available on the test device. Zero by default (no simulation). */
    private val simulatedExtraLatencyMs: Long = 0,
    private val simulatedLossRatio: Double = 0.0,
    /** 0 = let the OS pick an ephemeral port (the normal, production case). A specific port is
     *  only needed when something must know in advance where to reach this adapter - which is
     *  exactly [com.bigrocket.bonding.sandbox.BondingSandbox]'s situation, where two loopback
     *  peers must each target a port the other is listening on before either side has sent
     *  anything. */
    private val localPort: Int = 0,
) : PathAdapter {

    private var socket: DatagramSocket? = null
    private val receiveBuffer = ByteArray(RECEIVE_BUFFER_SIZE)

    private val nextHeartbeatSequence = AtomicLong(0)
    private val pendingHeartbeats = ConcurrentHashMap<Long, Long>() // sequence -> sent-at ms

    @Volatile private var latencyEmaMs: Double = PathMetrics.initial().latencyMs.toDouble()
    @Volatile private var jitterEmaMs: Double = PathMetrics.initial().jitterMs.toDouble()
    @Volatile private var lossRatio: Double = 0.0
    @Volatile private var bytesSentThisWindow: Long = 0
    @Volatile private var windowStartMs: Long = now()
    @Volatile private var bandwidthEstimate: Long = PathMetrics.initial().bandwidthEstimate
    @Volatile private var heartbeatsSent: Long = 0
    @Volatile private var heartbeatsAcked: Long = 0
    @Volatile private var connected: Boolean = false

    override fun connect() {
        val s = if (localPort != 0) DatagramSocket(localPort) else DatagramSocket()
        if (!vpnService.protect(s)) {
            s.close()
            throw java.io.IOException("VpnService.protect() failed for path $id")
        }
        network.bindSocket(s)
        s.soTimeout = SOCKET_POLL_TIMEOUT_MS
        socket = s
        connected = true
    }

    override fun disconnect() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        pendingHeartbeats.clear()
    }

    override fun send(frame: Frame) {
        val activeSocket = socket ?: throw java.io.IOException("Path $id is not connected")

        if (simulatedLossRatio > 0.0 && Random.nextDouble() < simulatedLossRatio) {
            // Simulated loss: pretend we sent it (so the sandbox's reorder/recovery logic sees a
            // genuine gap to react to), but never actually put it on the wire.
            return
        }

        val encoded = BinaryFrameCodec.encode(frame)
        if (simulatedExtraLatencyMs > 0) {
            // Deliberately blocking this path's own sender thread only - Section 10/336 ("هیچ
            // Path نباید دیگری را Block کند") is about paths not blocking EACH OTHER, which
            // holds here since every PathRuntime drains its queue on its own dedicated worker.
            Thread.sleep(simulatedExtraLatencyMs)
        }

        val packet = DatagramPacket(encoded, encoded.size, remoteAddress)
        activeSocket.send(packet)

        bytesSentThisWindow += encoded.size
        rolloverBandwidthWindowIfNeeded()
    }

    override fun receive(): Frame? {
        val activeSocket = socket ?: return null
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        try {
            activeSocket.receive(packet)
        } catch (_: java.net.SocketTimeoutException) {
            return null
        } catch (_: Exception) {
            return null // Section 464: fail safely, never throw out of receive()
        }

        val frame = BinaryFrameCodec.decode(packet.data.copyOf(packet.length)) ?: return null

        if (frame.header.type == MessageType.HEARTBEAT_ACK) {
            handleHeartbeatAck(frame)
            return null // control-plane frame, not handed up as data
        }
        if (frame.header.type == MessageType.HEARTBEAT) {
            replyToHeartbeat(frame)
            return null
        }
        return frame
    }

    override fun getMetrics(): PathMetrics = PathMetrics(
        latencyMs = latencyEmaMs.toLong(),
        jitterMs = jitterEmaMs.toLong(),
        packetLoss = lossRatio,
        bandwidthEstimate = bandwidthEstimate,
        timestamp = now(),
    )

    override fun isAvailable(): Boolean = connected && socket?.isClosed == false

    /** Section 64/139-141 (Heartbeat Mechanism): call periodically (e.g. every 1s) from the
     *  runtime layer to keep [getMetrics] fresh. Sends a HEARTBEAT the remote end (the
     *  sandbox's shared listener, or any future compatible remote) is expected to echo back as
     *  HEARTBEAT_ACK; [heartbeatsSent] vs [heartbeatsAcked] over a rolling window feeds
     *  [lossRatio] when a heartbeat times out without an ACK. */
    fun sendHeartbeat() {
        val seq = nextHeartbeatSequence.getAndIncrement()
        val sentAt = now()
        pendingHeartbeats[seq] = sentAt
        heartbeatsSent++

        val header = FrameHeader(
            version = FrameHeader.CURRENT_VERSION,
            type = MessageType.HEARTBEAT,
            streamId = streamId,
            sequence = seq,
            timestamp = sentAt,
            payloadSize = 0,
            flags = FrameFlags.CONTROL,
            checksum = 0L,
        )
        runCatching { send(Frame(header, ByteArray(0))) }

        expirePendingHeartbeats()
    }

    private fun handleHeartbeatAck(ackFrame: Frame) {
        val seq = ackFrame.header.sequence
        val sentAt = pendingHeartbeats.remove(seq) ?: return
        val rttMs = (now() - sentAt).coerceAtLeast(0)
        heartbeatsAcked++

        val previousLatency = latencyEmaMs
        // Exponential moving average (Section 169: "مستمر اما سبک" - continuous but light).
        latencyEmaMs = previousLatency * 0.75 + rttMs * 0.25
        jitterEmaMs = jitterEmaMs * 0.75 + abs(rttMs - previousLatency) * 0.25

        if (heartbeatsSent > 0) {
            lossRatio = 1.0 - (heartbeatsAcked.toDouble() / heartbeatsSent.toDouble())
        }
    }

    /** A heartbeat that never got an ACK within [HEARTBEAT_TIMEOUT_MS] counts toward loss
     *  (Section 65: Failure Detection - "Missed Heartbeat" -> health degradation) without
     *  waiting indefinitely for a reply that isn't coming. */
    private fun expirePendingHeartbeats() {
        val cutoff = now() - HEARTBEAT_TIMEOUT_MS
        val expired = pendingHeartbeats.entries.filter { it.value < cutoff }
        if (expired.isEmpty()) return
        expired.forEach { pendingHeartbeats.remove(it.key) }
        if (heartbeatsSent > 0) {
            lossRatio = 1.0 - (heartbeatsAcked.toDouble() / heartbeatsSent.toDouble())
        }
    }

    private fun replyToHeartbeat(heartbeat: Frame) {
        val header = FrameHeader(
            version = FrameHeader.CURRENT_VERSION,
            type = MessageType.HEARTBEAT_ACK,
            streamId = heartbeat.header.streamId,
            sequence = heartbeat.header.sequence,
            timestamp = now(),
            payloadSize = 0,
            flags = FrameFlags.CONTROL,
            checksum = 0L,
        )
        runCatching { send(Frame(header, ByteArray(0))) }
    }

    private fun rolloverBandwidthWindowIfNeeded() {
        val elapsed = now() - windowStartMs
        if (elapsed >= BANDWIDTH_WINDOW_MS) {
            bandwidthEstimate = (bytesSentThisWindow * 8_000L) / elapsed.coerceAtLeast(1) // bits/sec
            bytesSentThisWindow = 0
            windowStartMs = now()
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val RECEIVE_BUFFER_SIZE = 64 * 1024
        private const val SOCKET_POLL_TIMEOUT_MS = 200
        private const val HEARTBEAT_TIMEOUT_MS = 2_000L
        private const val BANDWIDTH_WINDOW_MS = 1_000L
    }
}

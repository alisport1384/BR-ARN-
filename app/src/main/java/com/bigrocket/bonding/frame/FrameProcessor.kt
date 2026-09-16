package com.bigrocket.bonding.frame

import java.util.concurrent.atomic.AtomicLong

/** Section 228/332: Raw Stream <-> Bond Frames. One [FrameProcessor] owns exactly one stream's
 *  sequence space (Section 131/366 - sequence numbers are per-stream, not global), so a fresh
 *  instance is created per [com.bigrocket.bonding.core.BondingEngine] stream/session. */
class FrameProcessor(private val streamId: Int, private val maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD) {

    private val nextSequence = AtomicLong(0)

    /** Section 229 (Fragmentation Algorithm): splits [data] into <= [maxPayloadSize] chunks,
     *  each becoming its own [Frame] with a unique, monotonically increasing sequence number
     *  (Section 132: "ارسال: Increment +1"). An empty [data] array produces an empty list, not a
     *  zero-length frame - there is nothing meaningful to bond-transmit. */
    fun createFrames(data: ByteArray): List<Frame> {
        if (data.isEmpty()) return emptyList()
        val frames = ArrayList<Frame>((data.size + maxPayloadSize - 1) / maxPayloadSize)
        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(maxPayloadSize, data.size - offset)
            val payload = data.copyOfRange(offset, offset + chunkSize)
            val header = FrameHeader(
                version = FrameHeader.CURRENT_VERSION,
                type = MessageType.DATA,
                streamId = streamId,
                sequence = nextSequence.getAndIncrement(),
                timestamp = System.nanoTime() / 1_000_000L,
                payloadSize = payload.size,
                flags = 0,
                checksum = 0L, // filled in by FrameCodec.encode at transmit time
            )
            frames.add(Frame(header, payload))
            offset += chunkSize
        }
        return frames
    }

    /** Section 332 `decode(frame): ByteArray` - extracts the raw payload from a single, already
     *  correctly-ordered data frame. Multi-frame stream reassembly across many frames is the
     *  [com.bigrocket.bonding.buffer.FrameReorderBuffer]'s job (Section 37/238), not this
     *  class's - Section 46 ("Reassembler نباید شبکه را بشناسد") draws the same boundary the
     *  other direction: this class only ever sees one frame at a time. */
    fun extractPayload(frame: Frame): ByteArray = frame.payload

    /** Builds a single control-plane frame (HEARTBEAT/SYNC/RECOVERY_REQUEST/etc., Section 63) -
     *  these share the stream's sequence space so gaps they leave behind are still accounted for
     *  correctly by the reorder buffer, but are marked [FrameFlags.CONTROL] so the data-stream
     *  reassembler (Section 46/238) skips them rather than trying to deliver them as payload. */
    fun createControlFrame(type: MessageType, payload: ByteArray = ByteArray(0)): Frame {
        val header = FrameHeader(
            version = FrameHeader.CURRENT_VERSION,
            type = type,
            streamId = streamId,
            sequence = nextSequence.getAndIncrement(),
            timestamp = System.nanoTime() / 1_000_000L,
            payloadSize = payload.size,
            flags = FrameFlags.CONTROL,
            checksum = 0L,
        )
        return Frame(header, payload)
    }

    companion object {
        /** Section 230/443: kept well under [BinaryFrameCodec]'s 64KB hard cap so a single UDP
         *  datagram (Section 300/301: UDP is the recommended internal transport) never has to
         *  fragment at the IP layer on a typical 1500-byte-MTU path even after header overhead -
         *  large enough for throughput (Section 230 "Large Frame = Higher Efficiency"), small
         *  enough to keep reorder-delay/latency low (Section 157: Processing Delay budget). */
        const val DEFAULT_MAX_PAYLOAD: Int = 1200
    }
}

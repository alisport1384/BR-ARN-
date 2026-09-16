package com.bigrocket.bonding.frame

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Section 209/322: Object <-> Binary <-> Network. */
interface FrameCodec {
    fun encode(frame: Frame): ByteArray

    /** Returns null (never throws) on any malformed input - Section 150/152/464: a decode
     *  failure is a normal, expected event ("Never Trust Input"), not an exceptional one. The
     *  caller is expected to drop the frame and continue (Section 431). */
    fun decode(data: ByteArray): Frame?
}

/**
 * Section 129 Frame Header v1 wire layout, fixed 42-byte header followed by the payload:
 *
 * ```
 * offset  size  field
 * 0       4     Magic Number      ("BR01" as ASCII bytes, Section 130)
 * 4       1     Protocol Version  (FrameHeader.CURRENT_VERSION)
 * 5       1     Message Type      (MessageType.ordinal)
 * 6       4     Stream ID
 * 10      8     Sequence Number
 * 18      8     Timestamp         (monotonic ms, Section 133)
 * 26      4     Payload Length
 * 30      4     Flags             (FrameFlags bit field, Section 134)
 * 34      8     Checksum          (CRC32 over bytes [4, 34) + payload, widened to 8 bytes)
 * 42      N     Payload
 * ```
 *
 * Checksum deliberately excludes the magic number (Section 152 validates magic *before*
 * checksum, as a fast reject for garbage that shouldn't even be hashed) and excludes itself.
 */
object BinaryFrameCodec : FrameCodec {

    /** "BR01" as ASCII, Section 130's `MAGIC = 0xBR01` made into a real, unambiguous 32-bit
     *  constant (0xBR01 is not valid hex - B and R are not hex digits - so this is the literal
     *  ASCII-byte reading of that mnemonic, which is what it was clearly meant to convey). */
    const val MAGIC: Int = 0x42523031

    private const val HEADER_SIZE = 42
    private const val MAX_PAYLOAD_SIZE = 64 * 1024 // Section 443: Maximum Size bound

    override fun encode(frame: Frame): ByteArray {
        val header = frame.header
        require(header.payloadSize == frame.payload.size) {
            "FrameHeader.payloadSize (${header.payloadSize}) does not match actual payload size (${frame.payload.size})"
        }
        require(frame.payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload exceeds MAX_PAYLOAD_SIZE ($MAX_PAYLOAD_SIZE)"
        }

        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(header.version.toByte())
        buffer.put(header.type.ordinal.toByte())
        buffer.putInt(header.streamId)
        buffer.putLong(header.sequence)
        buffer.putLong(header.timestamp)
        buffer.putInt(header.payloadSize)
        buffer.putInt(header.flags)
        val checksum = computeChecksum(header, frame.payload)
        buffer.putLong(checksum)
        buffer.put(frame.payload)
        return buffer.array()
    }

    override fun decode(data: ByteArray): Frame? {
        if (data.size < HEADER_SIZE) return null // Section 442: Payload Size / header too short

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        if (magic != MAGIC) return null // Section 130: Invalid Magic -> frame removed

        val version = buffer.get().toInt() and 0xFF
        if (version != FrameHeader.CURRENT_VERSION) return null // Section 444: Unknown Version -> reject

        val typeByte = buffer.get().toInt() and 0xFF
        val type = MessageType.fromWire(typeByte) ?: return null

        val streamId = buffer.int
        val sequence = buffer.long
        val timestamp = buffer.long
        val payloadSize = buffer.int
        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_SIZE) return null // Section 443
        if (data.size - HEADER_SIZE != payloadSize) return null // Section 442: Size Check

        val flags = buffer.int
        val checksum = buffer.long

        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        val header = FrameHeader(version, type, streamId, sequence, timestamp, payloadSize, flags, checksum)
        val expectedChecksum = computeChecksum(header, payload)
        if (expectedChecksum != checksum) return null // Section 135/152: Checksum Check -> reject

        return Frame(header, payload)
    }

    private fun computeChecksum(header: FrameHeader, payload: ByteArray): Long {
        val crc = CRC32()
        val fieldsBuffer = ByteBuffer.allocate(HEADER_SIZE - 4 /* magic */ - 8 /* checksum itself */)
            .order(ByteOrder.BIG_ENDIAN)
        fieldsBuffer.put(header.version.toByte())
        fieldsBuffer.put(header.type.ordinal.toByte())
        fieldsBuffer.putInt(header.streamId)
        fieldsBuffer.putLong(header.sequence)
        fieldsBuffer.putLong(header.timestamp)
        fieldsBuffer.putInt(header.payloadSize)
        fieldsBuffer.putInt(header.flags)
        crc.update(fieldsBuffer.array())
        crc.update(payload)
        return crc.value // CRC32.value is already an unsigned 32-bit value widened into a Long
    }
}

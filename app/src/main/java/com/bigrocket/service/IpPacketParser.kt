package com.bigrocket.service

import java.nio.ByteBuffer

enum class IpProtocol(val code: Int) {
    ICMP(1),
    TCP(6),
    UDP(17),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): IpProtocol = values().find { it.code == code } ?: UNKNOWN
    }
}

object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

data class ParsedIpPacket(
    val version: Int,
    val protocol: IpProtocol,
    val sourceIp: String,
    val destinationIp: String,
    val sourcePort: Int,
    val destinationPort: Int,
    // Offset/length of the transport-layer segment (header + data) within the buffer.
    val payloadOffset: Int,
    val payloadLength: Int,
    // Only populated when protocol == TCP.
    val tcpFlags: Int = 0,
    val tcpSeq: Long = 0L,
    val tcpAck: Long = 0L,
    val tcpWindow: Int = 0,
    val tcpHeaderLength: Int = 0
) {
    /** Offset of the actual application payload, past the transport header. */
    val dataOffset: Int
        get() = if (protocol == IpProtocol.TCP) payloadOffset + tcpHeaderLength else payloadOffset + 8

    /** Length of the actual application payload, past the transport header. */
    val dataLength: Int
        get() = (payloadLength - (dataOffset - payloadOffset)).coerceAtLeast(0)
}

object IpPacketParser {

    fun parse(buffer: ByteBuffer, length: Int): ParsedIpPacket? {
        if (length < 20) return null // Minimum IPv4 header length

        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // Only IPv4 is supported at this layer

        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl) return null
        val protocolCode = buffer.get(9).toInt() and 0xFF
        val protocol = IpProtocol.fromCode(protocolCode)

        val srcIpBytes = ByteArray(4)
        val dstIpBytes = ByteArray(4)

        buffer.position(12)
        buffer.get(srcIpBytes)
        buffer.get(dstIpBytes)

        val sourceIp = srcIpBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val destinationIp = dstIpBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

        var srcPort = 0
        var dstPort = 0
        var tcpFlags = 0
        var tcpSeq = 0L
        var tcpAck = 0L
        var tcpWindow = 0
        var tcpHeaderLength = 0

        if ((protocol == IpProtocol.TCP || protocol == IpProtocol.UDP) && length >= ihl + 4) {
            srcPort = (buffer.getShort(ihl).toInt() and 0xFFFF)
            dstPort = (buffer.getShort(ihl + 2).toInt() and 0xFFFF)
        }

        if (protocol == IpProtocol.TCP && length >= ihl + 20) {
            tcpSeq = buffer.getInt(ihl + 4).toLong() and 0xFFFFFFFFL
            tcpAck = buffer.getInt(ihl + 8).toLong() and 0xFFFFFFFFL
            val dataOffsetByte = buffer.get(ihl + 12).toInt() and 0xFF
            tcpHeaderLength = ((dataOffsetByte shr 4) and 0x0F) * 4
            tcpFlags = buffer.get(ihl + 13).toInt() and 0xFF
            tcpWindow = buffer.getShort(ihl + 14).toInt() and 0xFFFF
            if (tcpHeaderLength < 20 || length < ihl + tcpHeaderLength) {
                tcpHeaderLength = 20 // fall back to a sane minimum rather than reject the packet
            }
        }

        return ParsedIpPacket(
            version = version,
            protocol = protocol,
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            sourcePort = srcPort,
            destinationPort = dstPort,
            payloadOffset = ihl,
            payloadLength = length - ihl,
            tcpFlags = tcpFlags,
            tcpSeq = tcpSeq,
            tcpAck = tcpAck,
            tcpWindow = tcpWindow,
            tcpHeaderLength = tcpHeaderLength
        )
    }
}

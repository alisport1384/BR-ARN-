package com.bigrocket.service

import java.nio.ByteBuffer

object IpPacketBuilder {

    fun buildUdpResponsePacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        data: ByteArray,
        dataLength: Int
    ): ByteArray {
        val safeDataLength = dataLength.coerceAtLeast(0).coerceAtMost(data.size)
        val udpLen = 8 + safeDataLength
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)
        val buffer = ByteBuffer.wrap(packet)

        val srcIpBytes = parseIpAddress(srcIp)
        val dstIpBytes = parseIpAddress(dstIp)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        buffer.putShort(2, totalLen.toShort())
        buffer.putShort(4, 0.toShort())
        buffer.putShort(6, 0x4000.toShort()) // Don't Fragment
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP Protocol Code
        buffer.putShort(10, 0.toShort()) // Checksum Placeholder
        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        // Calculate and write IPv4 Header Checksum
        val ipChecksum = computeIpChecksum(packet, 0, 20)
        buffer.putShort(10, ipChecksum.toShort())

        // UDP Header
        buffer.putShort(20, (srcPort and 0xFFFF).toShort())
        buffer.putShort(22, (dstPort and 0xFFFF).toShort())
        buffer.putShort(24, udpLen.toShort())
        buffer.putShort(26, 0.toShort()) // Checksum Placeholder

        // Copy Payload
        if (safeDataLength > 0) {
            System.arraycopy(data, 0, packet, 28, safeDataLength)
        }

        // Calculate and write UDP Checksum (including Pseudo-Header)
        val udpChecksum = computeTransportChecksum(
            srcIpBytes = srcIpBytes,
            dstIpBytes = dstIpBytes,
            protocol = 17,
            transportData = packet,
            transportOffset = 20,
            transportLen = udpLen
        )
        buffer.putShort(26, udpChecksum.toShort())

        return packet
    }

    fun buildTcpResponsePacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seqNumber: Long,
        ackNumber: Long,
        flags: Int,
        data: ByteArray,
        dataLength: Int,
        dataOffset: Int = 0
    ): ByteArray {
        val safeDataLength = dataLength.coerceAtLeast(0).coerceAtMost((data.size - dataOffset).coerceAtLeast(0))
        val tcpHeaderLen = 20
        val tcpLen = tcpHeaderLen + safeDataLength
        val totalLen = 20 + tcpLen
        val packet = ByteArray(totalLen)
        val buffer = ByteBuffer.wrap(packet)

        val srcIpBytes = parseIpAddress(srcIp)
        val dstIpBytes = parseIpAddress(dstIp)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        buffer.putShort(2, totalLen.toShort())
        buffer.putShort(4, 0.toShort())
        buffer.putShort(6, 0x4000.toShort()) // Don't Fragment
        packet[8] = 64.toByte() // TTL
        packet[9] = 6.toByte() // TCP Protocol Code
        buffer.putShort(10, 0.toShort()) // Checksum Placeholder
        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        // Calculate and write IPv4 Header Checksum
        val ipChecksum = computeIpChecksum(packet, 0, 20)
        buffer.putShort(10, ipChecksum.toShort())

        // TCP Header
        buffer.putShort(20, (srcPort and 0xFFFF).toShort())
        buffer.putShort(22, (dstPort and 0xFFFF).toShort())
        buffer.putInt(24, (seqNumber and 0xFFFFFFFFL).toInt())
        buffer.putInt(28, (ackNumber and 0xFFFFFFFFL).toInt())
        packet[32] = 0x50.toByte() // Data Offset: 5 dwords (20 bytes)
        packet[33] = (flags and 0xFF).toByte()
        buffer.putShort(34, 64240.toShort()) // Window Size
        buffer.putShort(36, 0.toShort()) // Checksum Placeholder
        buffer.putShort(38, 0.toShort()) // Urgent Pointer

        // Copy Payload
        if (safeDataLength > 0) {
            System.arraycopy(data, dataOffset, packet, 40, safeDataLength)
        }

        // Calculate and write TCP Checksum (including Pseudo-Header)
        val tcpChecksum = computeTransportChecksum(
            srcIpBytes = srcIpBytes,
            dstIpBytes = dstIpBytes,
            protocol = 6,
            transportData = packet,
            transportOffset = 20,
            transportLen = tcpLen
        )
        buffer.putShort(36, tcpChecksum.toShort())

        return packet
    }

    private fun parseIpAddress(ip: String): ByteArray {
        val parts = ip.split(".")
        require(parts.size == 4) { "Invalid IPv4 address format: $ip" }
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            bytes[i] = parts[i].toInt().coerceIn(0, 255).toByte()
        }
        return bytes
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.toInt() xor 0xFFFF) and 0xFFFF
    }

    private fun computeTransportChecksum(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        protocol: Int,
        transportData: ByteArray,
        transportOffset: Int,
        transportLen: Int
    ): Int {
        var sum = 0L

        // Pseudo-header: Source IP (4 bytes)
        for (i in 0 until 4 step 2) {
            sum += ((srcIpBytes[i].toInt() and 0xFF) shl 8) or (srcIpBytes[i + 1].toInt() and 0xFF)
        }
        // Pseudo-header: Destination IP (4 bytes)
        for (i in 0 until 4 step 2) {
            sum += ((dstIpBytes[i].toInt() and 0xFF) shl 8) or (dstIpBytes[i + 1].toInt() and 0xFF)
        }
        // Pseudo-header: Zero byte (1) + Protocol (1)
        sum += protocol and 0xFF
        // Pseudo-header: Transport Length (2 bytes)
        sum += transportLen and 0xFFFF

        // Transport Segment (Header + Data)
        var i = transportOffset
        val end = transportOffset + transportLen
        while (i < end - 1) {
            val word = ((transportData[i].toInt() and 0xFF) shl 8) or (transportData[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (transportData[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.toInt() xor 0xFFFF) and 0xFFFF
        return if (checksum == 0 && protocol == 17) 0xFFFF else checksum
    }
}
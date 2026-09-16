package com.bigrocket

import com.bigrocket.service.IpPacketParser
import com.bigrocket.service.IpProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class IpPacketTest {

    private val rawUdpPacketBytes = byteArrayOf(
        // IPv4 Header (20 bytes)
        0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1C.toByte(),
        0x1C.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
        0x40.toByte(), 0x11.toByte(), 0x73.toByte(), 0x4D.toByte(),
        0x7F.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), // 127.0.0.1
        0x7F.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), // 127.0.0.2
        // UDP Header (8 bytes)
        0x1F.toByte(), 0x90.toByte(), 0x00.toByte(), 0x35.toByte(),
        0x00.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte()
    )

    @Test
    fun testIpPacketParser_correctUdpHeaderParsing() {
        val buffer = ByteBuffer.wrap(rawUdpPacketBytes)
        val parsed = IpPacketParser.parse(buffer, rawUdpPacketBytes.size)

        assertNotNull("Valid packet should not be null", parsed)
        parsed?.let {
            assertEquals(IpProtocol.UDP, it.protocol)
            assertEquals(8080, it.sourcePort)
            assertEquals(53, it.destinationPort)
        }
    }

    @Test
    fun testInvalidPacketHandling_gracefulReturnNull() {
        val corruptedBytes = byteArrayOf(0x45.toByte(), 0x00.toByte())
        val buffer = ByteBuffer.wrap(corruptedBytes)

        val parsed = IpPacketParser.parse(buffer, corruptedBytes.size)
        assertNull("Corrupted packet should return null", parsed)
    }
}
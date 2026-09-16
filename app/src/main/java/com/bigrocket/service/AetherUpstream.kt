package com.bigrocket.service

import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Local SOCKS5 adapter used only when BigRocket -> Aether chaining is active. */
object AetherUpstream {
    private const val HOST = "127.0.0.1"
    private const val PORT = 1819
    private const val TIMEOUT_MS = 5000

    fun openTcp(vpnService: VpnService, destinationHost: String, destinationPort: Int): Socket {
        val socket = Socket()
        try {
            vpnService.protect(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS

            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            socksGreeting(out, input)

            val hostBytes = destinationHost.toByteArray(Charsets.UTF_8)
            if (hostBytes.size > 255) throw IOException("Destination hostname is too long")

            val request = ByteArrayOutputStream().apply {
                write(0x05)
                write(0x01) // CONNECT
                write(0x00)
                write(0x03) // DOMAIN: Aether resolves inside its tunnel.
                write(hostBytes.size)
                write(hostBytes)
                write((destinationPort ushr 8) and 0xFF)
                write(destinationPort and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()
            readReply(input)
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun openUdp(vpnService: VpnService): UdpAssociation {
        val control = Socket()
        val udp = DatagramSocket()
        try {
            vpnService.protect(control)
            vpnService.protect(udp)
            control.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            control.soTimeout = TIMEOUT_MS

            val out = control.getOutputStream()
            val input = DataInputStream(control.getInputStream())
            socksGreeting(out, input)

            // UDP ASSOCIATE with 0.0.0.0:0. Aether returns its loopback relay address.
            out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            val bound = readBoundAddress(input)
            control.soTimeout = 0
            udp.soTimeout = 1000
            return UdpAssociation(control, udp, bound)
        } catch (e: Exception) {
            runCatching { control.close() }
            runCatching { udp.close() }
            throw e
        }
    }

    class UdpAssociation internal constructor(
        private val control: Socket,
        private val socket: DatagramSocket,
        private val relay: InetSocketAddress,
    ) : AutoCloseable {
        fun send(destinationHost: String, destinationPort: Int, payload: ByteArray) {
            val address = InetAddress.getByName(destinationHost)
            val header = ByteArrayOutputStream().apply {
                write(0)
                write(0)
                write(0) // FRAG=0
                if (address.address.size == 16) {
                    write(4)
                    write(address.address)
                } else {
                    write(1)
                    write(address.address)
                }
                write((destinationPort ushr 8) and 0xFF)
                write(destinationPort and 0xFF)
                write(payload)
            }.toByteArray()
            socket.send(DatagramPacket(header, header.size, relay))
        }

        fun receive(buffer: ByteArray): ReceivedUdp? {
            val packet = DatagramPacket(buffer, buffer.size)
            return try {
                socket.receive(packet)
                parseReply(packet.data, packet.length)
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
        }

        override fun close() {
            runCatching { socket.close() }
            runCatching { control.close() }
        }
    }

    data class ReceivedUdp(val payload: ByteArray)

    private fun socksGreeting(out: java.io.OutputStream, input: DataInputStream) {
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val reply = ByteArray(2)
        input.readFully(reply)
        if (reply[0].toInt() != 5 || reply[1].toInt() != 0) {
            throw IOException("Aether SOCKS5 authentication negotiation failed")
        }
    }

    private fun readReply(input: DataInputStream) {
        val head = ByteArray(4)
        input.readFully(head)
        if (head[0].toInt() != 5 || head[1].toInt() != 0) {
            throw IOException("Aether SOCKS5 CONNECT rejected: ${head[1].toInt() and 0xFF}")
        }
        skipAddress(input, head[3].toInt() and 0xFF)
    }

    private fun readBoundAddress(input: DataInputStream): InetSocketAddress {
        val head = ByteArray(4)
        input.readFully(head)
        if (head[0].toInt() != 5 || head[1].toInt() != 0) {
            throw IOException("Aether SOCKS5 UDP ASSOCIATE rejected: ${head[1].toInt() and 0xFF}")
        }
        val address = when (head[3].toInt() and 0xFF) {
            1 -> ByteArray(4).also { input.readFully(it) }.let(InetAddress::getByAddress)
            4 -> ByteArray(16).also { input.readFully(it) }.let(InetAddress::getByAddress)
            else -> throw IOException("Unsupported SOCKS5 UDP relay address type")
        }
        val port = input.readUnsignedShort()
        return InetSocketAddress(address, port)
    }

    private fun skipAddress(input: DataInputStream, atyp: Int) {
        when (atyp) {
            1 -> input.skipBytes(4)
            4 -> input.skipBytes(16)
            3 -> input.skipBytes(input.readUnsignedByte())
            else -> throw IOException("Unsupported SOCKS5 reply address type")
        }
        input.skipBytes(2)
    }

    private fun parseReply(data: ByteArray, length: Int): ReceivedUdp? {
        if (length < 4 || data[0].toInt() != 0 || data[1].toInt() != 0 || data[2].toInt() != 0) return null
        var offset = 3
        val atyp = data[offset++].toInt() and 0xFF
        when (atyp) {
            1 -> offset += 4
            3 -> {
                if (offset >= length) return null
                offset += 1 + (data[offset].toInt() and 0xFF)
            }
            4 -> offset += 16
            else -> return null
        }
        if (offset + 2 > length) return null
        offset += 2
        if (offset > length) return null
        return ReceivedUdp(data.copyOfRange(offset, length))
    }
}

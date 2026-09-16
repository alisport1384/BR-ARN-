package com.bigrocket.service

import android.net.Network
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 server that [HevTunnel] forwards every TUN packet to as plain SOCKS5,
 * and that Aether's embedded engine uses as its upstreamProxy (socks5://127.0.0.1:12347).
 *
 * FIX 2026-09-16 WireGuard/Gool "socks5 listener did not become ready":
 * Root cause was TCP CONNECT to api.cloudflareclient.com:443 failing with 20s idle
 * timeout. BondingSocksServer resolved via cellular to IPv6 2606:4700::... only (first
 * result of getAllByName) and that IPv6 path was blackholed on the carrier, so
 * registration retry loop in Rust never succeeded and engine never opened 127.0.0.1:1819.
 * Fix: try ALL networks sorted by weight, try ALL resolved IPs with IPv4 preferred,
 * fast fallback on connect failure, and fix UDP ASSOCIATE receiver to use actual
 * source address instead of first-request host/port.
 */
class BondingSocksServer(
    private val vpnService: VpnService,
    private val path3Router: Path3Router
) {

    companion object {
        /** 127.0.0.1-only; matches TunnelConfig.SOCKS_PORT so Aether's PortProbe.awaitOpen succeeds. */
        const val PORT = 1819
        private const val CONNECT_TIMEOUT_MS = 5000
        // Bounds how long a TCP relay direction blocks with no data at all after the
        // connection is established - separate from CONNECT_TIMEOUT_MS.
        private const val RELAY_READ_TIMEOUT_MS = 20_000
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L
        private const val UDP_RECEIVE_TIMEOUT_MS = 1000
    }

    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50
    @Volatile private var upstreamMode = UpstreamMode.NONE

    private val relayIdCounter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Every open relay (TCP or UDP), tagged with which physical Network it's using */
    private val activeRelays = ConcurrentHashMap<Int, ActiveRelay>()

    private class ActiveRelay(@Volatile var network: Network?, val close: () -> Unit)

    fun start() {
        if (serverSocket != null) return
        val server = ServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeRelays.values.toList().forEach { runCatching { it.close() } }
        activeRelays.clear()
    }

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        path3Router.updateNetworks(wifi, cellular)
    }

    fun updateWeights(wifiW: Int, cellularW: Int) {
        wifiWeight = wifiW
        cellularWeight = cellularW
        path3Router.updateWeights(wifiW, cellularW)
    }

    fun setUpstreamMode(mode: UpstreamMode) {
        upstreamMode = mode
    }

    fun notifySoftFailure(deadNetwork: Network) {
        activeRelays.entries.toList().forEach { (id, relay) ->
            if (relay.network == deadNetwork) {
                runCatching { relay.close() }
                activeRelays.remove(id)
            }
        }
    }

    /** Deterministic best-path pick - see original doc in Path3Router */
    private fun pickBestNetwork(): Network? = path3Router.selectNetwork()

    // --- SOCKS5 server handshake ------------------------------------------------------

    private suspend fun handleClient(client: Socket) {
        val relayId = relayIdCounter.getAndIncrement()
        try {
            client.tcpNoDelay = true
            client.soTimeout = CONNECT_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) return closeQuietly(client)
            if (greeting[0].toInt() != 0x05) return closeQuietly(client)
            val methodCount = greeting[1].toInt() and 0xFF
            if (methodCount > 0 && !readFully(input, ByteArray(methodCount))) return closeQuietly(client)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val head = ByteArray(4)
            if (!readFully(input, head)) return closeQuietly(client)
            val cmd = head[1].toInt() and 0xFF
            val destination = readAddress(input, head[3].toInt() and 0xFF) ?: return closeQuietly(client)

            client.soTimeout = 0
            when (cmd) {
                0x01 -> handleConnect(relayId, client, input, output, destination.first, destination.second)
                0x03 -> handleUdpAssociate(relayId, client, output)
                else -> {
                    output.write(socksReply(0x07))
                    output.flush()
                    closeQuietly(client)
                }
            }
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            closeQuietly(client)
        }
    }

    private fun readAddress(input: InputStream, atyp: Int): Pair<String, Int>? {
        val host = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            0x03 -> {
                val lenByte = ByteArray(1)
                if (!readFully(input, lenByte)) return null
                val len = lenByte[0].toInt() and 0xFF
                val domain = ByteArray(len)
                if (len > 0 && !readFully(input, domain)) return null
                String(domain, Charsets.US_ASCII)
            }
            0x04 -> {
                val addr = ByteArray(16)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            else -> return null
        }
        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return host to port
    }

    // --- TCP CONNECT -------------------------------------------------------------------
    /**
     * FIX for WireGuard/Gool SOCKS5 readiness:
     * - Tries ALL networks sorted by weight (not just best)
     * - For each network, tries ALL resolved IPs with IPv4 preferred
     * - Fast failover: if IPv6 connect succeeds but then times out on relay,
     *   next registration retry will try IPv4 (because we now try IPv4 first)
     * - Logs all attempts for diagnostics
     */
    private suspend fun handleConnect(
        relayId: Int,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        val mode = upstreamMode
        val remote: Socket
        val network: Network?
        try {
            if (mode == UpstreamMode.AETHER) {
                network = null
                remote = AetherUpstream.openTcp(vpnService, destHost, destPort)
            } else {
                val networks = path3Router.allNetworksSorted()
                if (networks.isEmpty()) throw IOException("No usable network")

                var lastError: Exception? = null
                var connectedSocket: Socket? = null
                var connectedNetwork: Network? = null
                var connectedAddr: InetAddress? = null

                // Try each network in weight order with IPv4-first fallback
                for (net in networks) {
                    val netLabel = path3Router.describeNetwork(net)
                    val resolvedList = try {
                        net.getAllByName(destHost).toList()
                    } catch (e: Exception) {
                        lastError = e
                        AppLogger.log("Path3", "DNS failed for $destHost via $netLabel: ${e.message}")
                        continue
                    }
                    if (resolvedList.isEmpty()) {
                        AppLogger.log("Path3", "DNS empty for $destHost via $netLabel")
                        continue
                    }
                    // IPv4 preferred: mobile carriers often have broken IPv6 to Cloudflare API
                    val sortedAddrs = resolvedList.sortedWith(compareBy(
                        { it is Inet6Address },
                        { it.hostAddress }
                    ))
                    AppLogger.log(
                        "Path3",
                        "TCP CONNECT trying net=$netLabel dest=$destHost:$destPort resolved=${sortedAddrs.joinToString { it.hostAddress }}",
                    )
                    for (addr in sortedAddrs) {
                        val socket = try {
                            networkSocket(net)
                        } catch (e: Exception) {
                            lastError = e
                            continue
                        }
                        try {
                            if (!vpnService.protect(socket)) throw IOException("protect failed for $netLabel")
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(addr, destPort), CONNECT_TIMEOUT_MS)
                            socket.soTimeout = RELAY_READ_TIMEOUT_MS
                            connectedSocket = socket
                            connectedNetwork = net
                            connectedAddr = addr
                            break
                        } catch (e: Exception) {
                            lastError = e
                            AppLogger.log("Path3", "TCP connect failed $destHost:$destPort via $netLabel -> ${addr.hostAddress}: ${e.message}")
                            runCatching { socket.close() }
                        }
                    }
                    if (connectedSocket != null) break
                }

                // Fallback to system DNS if network-bound DNS all failed
                if (connectedSocket == null) {
                    try {
                        val sysAddrs = InetAddress.getAllByName(destHost).toList()
                            .sortedWith(compareBy({ it is Inet6Address }, { it.hostAddress }))
                        AppLogger.log("Path3", "TCP CONNECT fallback system DNS for $destHost -> ${sysAddrs.joinToString { it.hostAddress }}")
                        for (net in networks) {
                            for (addr in sysAddrs) {
                                val socket = try { networkSocket(net) } catch (_: Exception) { continue }
                                try {
                                    if (!vpnService.protect(socket)) throw IOException("protect failed")
                                    socket.tcpNoDelay = true
                                    socket.connect(InetSocketAddress(addr, destPort), CONNECT_TIMEOUT_MS)
                                    socket.soTimeout = RELAY_READ_TIMEOUT_MS
                                    connectedSocket = socket
                                    connectedNetwork = net
                                    connectedAddr = addr
                                    break
                                } catch (e: Exception) {
                                    lastError = e
                                    runCatching { socket.close() }
                                }
                            }
                            if (connectedSocket != null) break
                        }
                    } catch (_: Exception) {
                    }
                }

                val socket = connectedSocket ?: throw lastError ?: IOException("All networks failed for $destHost:$destPort")
                AppLogger.log(
                    "Path3",
                    "TCP CONNECT pin chosen=${path3Router.describeNetwork(connectedNetwork)} -> ${connectedAddr?.hostAddress}:$destPort dest=$destHost:$destPort",
                )
                network = connectedNetwork
                remote = socket
            }
        } catch (e: Exception) {
            AppLogger.log("Path3", "TCP CONNECT failed dest=$destHost:$destPort error=${e.message}")
            runCatching { clientOut.write(socksReply(0x01)); clientOut.flush() }
            closeQuietly(client)
            return
        }

        activeRelays[relayId] = ActiveRelay(network) {
            runCatching { client.close() }
            runCatching { remote.close() }
        }

        try {
            clientOut.write(socksReply(0x00))
            clientOut.flush()
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            runCatching { remote.close() }
            closeQuietly(client)
            return
        }

        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()
        runCatching { client.soTimeout = RELAY_READ_TIMEOUT_MS }

        val upload = scope.launch { pipe(clientIn, remoteOut, "upload dest=$destHost:$destPort") }
        val download = scope.launch { pipe(remoteIn, clientOut, "download dest=$destHost:$destPort") }
        upload.join()
        download.join()

        activeRelays.remove(relayId)
        runCatching { remote.close() }
        closeQuietly(client)
    }

    private fun networkSocket(network: Network): Socket =
        network.socketFactory.createSocket()

    private fun pipe(from: InputStream, to: OutputStream, label: String) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
                TrafficStats.recordBytes(n)
            }
        } catch (_: SocketTimeoutException) {
            AppLogger.log("Path3", "relay timeout ($label) after ${RELAY_READ_TIMEOUT_MS}ms idle")
        } catch (_: Exception) {
        } finally {
            runCatching { to.flush() }
        }
    }

    // --- UDP ASSOCIATE -------------------------------------------------------------------

    private suspend fun handleUdpAssociate(relayId: Int, client: Socket, clientOut: OutputStream) {
        val localUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        vpnService.protect(localUdp)
        AppLogger.log("Path3", "UDP ASSOCIATE opened, localUdp bound to 127.0.0.1:${localUdp.localPort}")
        val mode = upstreamMode
        val aetherAssociation = if (mode == UpstreamMode.AETHER) {
            try {
                AetherUpstream.openUdp(vpnService)
            } catch (_: Exception) {
                null
            }
        } else null

        val reply = socksReply(0x00, InetAddress.getByName("127.0.0.1"), localUdp.localPort)
        try {
            clientOut.write(reply)
            clientOut.flush()
        } catch (_: Exception) {
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            closeQuietly(client)
            return
        }

        var pinnedSocket: DatagramSocket? = null
        var pinnedNetwork: Network? = null
        var receiverJob: Job? = null

        fun bindPinnedSocket(network: Network): DatagramSocket? = runCatching {
            val s = DatagramSocket()
            if (!vpnService.protect(s)) { s.close(); return@runCatching null }
            network.bindSocket(s)
            s.soTimeout = UDP_RECEIVE_TIMEOUT_MS
            s
        }.getOrNull()

        /**
         * FIX: Receiver now encodes reply with actual source address from Internet,
         * not with fixed host/port from first request. This fixes Gool/WG scanning
         * where Aether sends probes to many different endpoints and expects replies
         * to be attributed to correct endpoint.
         * Previously it captured host/port from first packet and reused for all,
         * causing misattribution during scan of ~285 candidates.
         */
        fun startPinnedReceiver(socket: DatagramSocket, clientAddr: InetSocketAddress): Job =
            scope.launch {
                val respBuf = ByteArray(64 * 1024)
                try {
                    while (isActive && !socket.isClosed) {
                        val resp = DatagramPacket(respBuf, respBuf.size)
                        try {
                            socket.receive(resp)
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (_: Exception) {
                            break
                        }
                        // Use actual source of reply, not first request's host/port
                        val srcAddr = resp.address
                        val srcPort = resp.port
                        val encoded = encodeSocksUdp(srcAddr.hostAddress, srcPort, resp.data.copyOf(resp.length))
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, clientAddr)) }
                        TrafficStats.recordBytes(resp.length)
                    }
                } catch (_: Exception) {
                }
            }

        val controlWatcher = scope.launch {
            try {
                val buf = ByteArray(1)
                while (client.getInputStream().read(buf) >= 0) { }
            } catch (_: Exception) {
            }
        }

        activeRelays[relayId] = ActiveRelay(null) {
            runCatching { client.close() }
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { pinnedSocket?.close() }
            receiverJob?.cancel()
        }

        val buffer = ByteArray(64 * 1024)
        var lastActivity = System.currentTimeMillis()
        var clientUdpAddr: InetSocketAddress? = null
        localUdp.soTimeout = UDP_RECEIVE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() - lastActivity < UDP_IDLE_TIMEOUT_MS) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localUdp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                lastActivity = System.currentTimeMillis()
                val decoded = decodeSocksUdp(packet.data, packet.length)
                if (decoded == null) {
                    AppLogger.log("Path3", "UDP ASSOCIATE decode FAILED")
                    continue
                }
                val fromAddr = packet.socketAddress as? InetSocketAddress ?: continue
                // Remember client address for receiver (first packet)
                if (clientUdpAddr == null) clientUdpAddr = fromAddr

                if (aetherAssociation != null) {
                    runCatching { aetherAssociation.send(decoded.host, decoded.port, decoded.payload) }
                    val received = runCatching { aetherAssociation.receive(buffer) }.getOrNull()
                    if (received != null) {
                        val encoded = encodeSocksUdp(decoded.host, decoded.port, received.payload)
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, fromAddr)) }
                    }
                    continue
                }

                if (pinnedSocket == null) {
                    // Try all networks sorted by weight for initial bind
                    val networks = path3Router.allNetworksSorted()
                    var bound = false
                    for (net in networks) {
                        val sock = bindPinnedSocket(net) ?: continue
                        pinnedSocket = sock
                        pinnedNetwork = net
                        activeRelays[relayId]?.network = net
                        AppLogger.log(
                            "Path3",
                            "UDP ASSOCIATE pin chosen=${path3Router.describeNetwork(net)} wifiWeight=$wifiWeight cellularWeight=$cellularWeight dest=${decoded.host}:${decoded.port}",
                        )
                        // Start receiver with actual client addr, not fixed host/port
                        receiverJob = startPinnedReceiver(sock, fromAddr)
                        bound = true
                        break
                    }
                    if (!bound) continue
                }
                var socket = pinnedSocket ?: continue
                var sendOk = false
                var attempts = 0
                while (!sendOk && attempts < 2) {
                    attempts++
                    try {
                        val resolvedList = try {
                            pinnedNetwork?.getAllByName(decoded.host)?.toList()
                                ?: InetAddress.getAllByName(decoded.host).toList()
                        } catch (_: Exception) {
                            listOf(InetAddress.getByName(decoded.host))
                        }
                        val sorted = resolvedList.sortedWith(compareBy({ it is Inet6Address }, { it.hostAddress }))
                        val targetAddr = sorted.firstOrNull() ?: InetAddress.getByName(decoded.host)
                        val dest = InetSocketAddress(targetAddr, decoded.port)
                        socket.send(DatagramPacket(decoded.payload, decoded.payload.size, dest))
                        sendOk = true
                    } catch (e: Exception) {
                        AppLogger.log("Path3", "UDP send failed via ${path3Router.describeNetwork(pinnedNetwork)} to ${decoded.host}:${decoded.port} err=${e.message}, trying fallback")
                        // Try fallback to other network
                        if (attempts == 1) {
                            val networks = path3Router.allNetworksSorted().filter { it != pinnedNetwork }
                            for (net in networks) {
                                val newSock = bindPinnedSocket(net)
                                if (newSock != null) {
                                    runCatching { pinnedSocket?.close() }
                                    receiverJob?.cancel()
                                    pinnedSocket = newSock
                                    pinnedNetwork = net
                                    activeRelays[relayId]?.network = net
                                    socket = newSock
                                    receiverJob = startPinnedReceiver(newSock, fromAddr)
                                    AppLogger.log("Path3", "UDP ASSOCIATE fallback to ${path3Router.describeNetwork(net)}")
                                    break
                                }
                            }
                        }
                    }
                }
                if (sendOk) TrafficStats.recordBytes(decoded.payload.size)
            }
        } finally {
            controlWatcher.cancel()
            activeRelays.remove(relayId)
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { pinnedSocket?.close() }
            receiverJob?.cancel()
            closeQuietly(client)
        }
    }

    private data class DecodedUdp(val host: String, val port: Int, val payload: ByteArray)

    private fun decodeSocksUdp(data: ByteArray, length: Int): DecodedUdp? {
        if (length < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        val atyp = data[3].toInt() and 0xFF
        var offset = 4
        val host: String
        when (atyp) {
            0x01 -> {
                if (offset + 4 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress
                offset += 4
            }
            0x03 -> {
                if (offset >= length) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (offset + len > length) return null
                host = String(data, offset, len, Charsets.US_ASCII)
                offset += len
            }
            0x04 -> {
                if (offset + 16 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress
                offset += 16
            }
            else -> return null
        }
        if (offset + 2 > length) return null
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return DecodedUdp(host, port, data.copyOfRange(offset, length))
    }

    private fun encodeSocksUdp(host: String, port: Int, payload: ByteArray): ByteArray {
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            // If host is not resolvable (should not happen for reply path), fallback to 0.0.0.0
            InetAddress.getByName("0.0.0.0")
        }
        val addrBytes = addr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    // --- helpers -------------------------------------------------------------------------

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = try {
                input.read(buffer, read, buffer.size - read)
            } catch (_: Exception) {
                return false
            }
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun socksReply(rep: Int, boundAddr: InetAddress = InetAddress.getByName("0.0.0.0"), boundPort: Int = 0): ByteArray {
        val addrBytes = boundAddr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0x05); out.write(rep); out.write(0x00)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((boundPort ushr 8) and 0xFF)
        out.write(boundPort and 0xFF)
        return out.toByteArray()
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }
}

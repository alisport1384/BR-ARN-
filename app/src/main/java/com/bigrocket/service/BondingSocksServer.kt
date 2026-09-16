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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 server that [HevTunnel] (hev-socks5-tunnel, a real userspace TCP/IP stack)
 * forwards every TUN packet to as plain SOCKS5, in place of BigRocket's own hand-rolled
 * IP-packet parser/relay ([TunPacketRouter]/[TcpRelayEngine]/[UdpRelayEngine]).
 *
 * hev already terminates the device's TCP connections properly (retransmission, ordering,
 * congestion control - the actual point of switching to it); this class only needs to do
 * what BigRocket's bonding has always done - pick Wi-Fi vs Cellular per new connection by
 * weight, protect() the socket, and relay real bytes - once per SOCKS5 request rather than
 * once per raw IP flow. That's also why this doesn't reuse TcpRelayEngine/UdpRelayEngine:
 * their code is built around parsing raw IP/TCP/UDP headers and hand-building reply packets
 * (RST, etc.), none of which applies here - hev owns that layer now.
 *
 * Kept as a self-contained alternate path (TunPacketRouter is untouched) specifically so
 * this can be A/B compared and trivially reverted - see BigRocketVpnService.USE_HEV_TUNNEL.
 */
class BondingSocksServer(
    private val vpnService: VpnService,
    private val path3Router: Path3Router
) {

    companion object {
        /** 127.0.0.1-only; picked to avoid AetherUpstream's own 1819 and any other local port. */
        const val PORT = 12347
        private const val CONNECT_TIMEOUT_MS = 5000
        // Section: bounds how long a TCP relay direction blocks with no data at all after the
        // connection is established - separate from CONNECT_TIMEOUT_MS, which only bounds the
        // initial handshake. Well under Aether's own ~90s internal TLS-handshake timeout
        // observed in practice, so our own relay fails fast and Aether's retry/reconnect logic
        // gets a chance to try again (a different edge, a different path) sooner rather than
        // waiting out a much longer OS-level TCP stall.
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

    /** Every open relay (TCP or UDP), tagged with which physical Network it's using, so a
     * soft-failure eviction (see [notifySoftFailure]) can close exactly the ones pinned to a
     * path that just went bad - mirrors NetworkSessionTracker's role for the old router. */
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

    /** Mirrors TunPacketRouter.notifySoftFailure: evict every relay pinned to [deadNetwork]
     * immediately instead of leaving it to fail silently - see the extended reasoning on
     * TunPacketRouter.notifySoftFailure itself, which applies identically here. */
    fun notifySoftFailure(deadNetwork: Network) {
        activeRelays.entries.toList().forEach { (id, relay) ->
            if (relay.network == deadNetwork) {
                runCatching { relay.close() }
                activeRelays.remove(id)
            }
        }
    }

    /** Deterministic best-path pick. This class has exactly one instance
     *  (BigRocketVpnService.bondingUpstream), used exclusively as Aether's own upstreamProxy -
     *  every connection/association it ever handles (TCP CONNECT for a GOOL/TCP tunnel, UDP
     *  ASSOCIATE for a WireGuard/MASQUE tunnel) is one long-lived flow for the whole VPN
     *  session, not one of many short-lived ones. A weighted-random sample is only meaningful
     *  when it is drawn many times so the outcome converges to the configured ratio; drawn
     *  exactly once, it just as often lands on the low-weight path outright and pins the
     *  entire session there. This never uses chance:
     *  unequal weights mean the app's own engine (DynamicWeightCalculator) has an authoritative
     *  answer already (a user-set score difference, or a measured quality difference), so the
     *  higher-weight network wins outright; an exact tie is resolved by that same engine's own
     *  identity tie-break rule (recent measured latency - see preferredIdentityPath), not by a
     *  fresh coin flip here. An exact tie only happens when the user has given both paths the
     *  same score (unequal scores always produce unequal weights - see
     *  DynamicWeightCalculator.preferredWeights), so it is rare and, either way, genuinely
     *  arbitrary: Wi-Fi is picked, fixed and not random. (DynamicWeightCalculator's own
     *  identity/IP tie-break is deliberately not reused here - it mutates a separate sticky
     *  identity-owner state meant for a different feature, and calling it here would silently
     *  decide/consume that state as a side effect of an unrelated bonding pin.) */
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
                // pickBestNetwork(), not pickNetwork(): this branch only ever carries Aether's
                // own outbound connections (this server instance is Aether's dedicated
                // upstreamProxy - see BigRocketVpnService/EmbeddedAetherRuntime), and a
                // GOOL/TCP tunnel is one long-lived connection for the whole session, same as
                // the UDP-associate case above - a single weighted-random sample would just as
                // often pin the whole session to the low-weight path. See pickBestNetwork's doc.
                val picked = pickBestNetwork() ?: throw IOException("No usable network")
                AppLogger.log(
                    "Path3",
                    "TCP CONNECT pin chosen=${path3Router.describeNetwork(picked)} wifiWeight=$wifiWeight cellularWeight=$cellularWeight dest=$destHost:$destPort",
                )
                network = picked
                // Protecting a socket only prevents VPN recursion; it does NOT select the
                // physical uplink. The selected Network must create/bind the socket, otherwise
                // Android is free to use the default network (typically Wi-Fi), defeating
                // BigRocket's path selection.
                val socket = networkSocket(picked)
                if (!vpnService.protect(socket)) throw IOException("Unable to protect TCP socket from VPN")
                socket.tcpNoDelay = true
                // Resolve destHost via picked.getAllByName(), not InetSocketAddress(String, Int)
                // - the latter uses the system-default DNS resolver, which is not bound to
                // `picked` (or to any protected socket) at all. A SOCKS5 client that sends a
                // hostname rather than a pre-resolved IP (reqwest's SOCKS5 support does exactly
                // this) would then have its DNS lookup go through an unrelated, unprotected
                // path - independent of whatever weight/network was actually selected for the
                // data connection, and liable to recurse into this VPN's own TUN. Network.getAllByName
                // is safe to call unconditionally: for an already-literal IP it just parses it,
                // same as InetAddress.getByName would, with no real query.
                val resolved = picked.getAllByName(destHost).firstOrNull()
                    ?: throw IOException("DNS resolution failed for $destHost on ${path3Router.describeNetwork(picked)}")
                AppLogger.log("Path3", "resolved $destHost -> ${resolved.hostAddress} via ${path3Router.describeNetwork(picked)}")
                socket.connect(InetSocketAddress(resolved, destPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = RELAY_READ_TIMEOUT_MS
                remote = socket
            }
        } catch (_: Exception) {
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
            // Section: no response within RELAY_READ_TIMEOUT_MS - most likely the remote (or a
            // DPI box in between) silently dropped the connection post-handshake rather than
            // resetting it, which a bare TCP read would otherwise block on indefinitely. Logged
            // distinctly from a normal close so this is visible without needing full verbosity.
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

        // Single-path state for the real-dial (NONE-mode, aetherAssociation == null) branch:
        // exactly one raw socket, bound once via the same deterministic pickBestNetwork() the
        // TCP CONNECT branch uses, for this association's entire lifetime.
        //
        // This branch carries Aether's own WireGuard/GOOL tunnel (BondingSocksServer is
        // Aether's configured upstreamProxy - see EmbeddedAetherRuntime). An earlier version
        // of this code alternated the source socket per outgoing packet on the theory that
        // WireGuard tolerates roaming (it identifies a peer by session key, not source IP).
        // That assumption is true for the CLIENT's outbound side but not for the resulting
        // downlink: a roaming-capable peer sends ALL return traffic to whichever source
        // address it most recently saw a valid packet from - a single "current endpoint",
        // not both at once. Alternating the source every packet made the server's notion of
        // "current endpoint" thrash on every packet, so the download direction (the bulk of
        // any real transfer) collapsed onto whichever path happened to win that race - in
        // practice mostly Wi-Fi - which is exactly the single-path-saturation symptom this
        // was meant to fix. Pinning one physical path per association removes the thrash;
        // weight is honored across associations/reconnects instead of within one, the same
        // flow-level granularity already used everywhere else in this file and in
        // TunPacketRouter.
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

        fun startPinnedReceiver(socket: DatagramSocket, host: String, port: Int, clientAddr: InetSocketAddress): Job =
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
                        val encoded = encodeSocksUdp(host, port, resp.data.copyOf(resp.length))
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, clientAddr)) }
                        TrafficStats.recordBytes(resp.length)
                    }
                } catch (_: Exception) {
                }
            }

        // TUN ASSOCIATE: per strict SOCKS5 (RFC 1928), the UDP association should end when the
        // control TCP connection closes. In practice, Aether's own client appears to close the
        // control connection promptly after receiving the ASSOCIATE reply - reasonable when
        // scanning ~285 candidates in parallel, since holding that many TCP connections open
        // simultaneously just to keep each UDP association alive would be wasteful - and only
        // sends the actual UDP probe packet afterward. Tearing down localUdp immediately on
        // control-connection EOF would race against that and lose almost every time, which is
        // exactly what was observed: the ASSOCIATE handshake succeeding (Aether logs receiving
        // the port) but no UDP packet ever actually being decoded on our side. So this
        // deliberately does NOT close localUdp here - UDP_IDLE_TIMEOUT_MS below is the sole
        // cleanup mechanism, which is already generous enough (60s) relative to any single
        // probe's few-second window.
        val controlWatcher = scope.launch {
            try {
                val buf = ByteArray(1)
                while (client.getInputStream().read(buf) >= 0) { /* control channel stays open */ }
            } catch (_: Exception) {
            }
        }

        // Registered once the pinned path is known (first packet); a soft-failure on that
        // specific Network then tears this association down like any single-path relay,
        // letting the tunnel reconnect and get a fresh weighted pick on the surviving path.
        activeRelays[relayId] = ActiveRelay(null) {
            runCatching { client.close() }
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { pinnedSocket?.close() }
            receiverJob?.cancel()
        }

        val buffer = ByteArray(64 * 1024)
        var lastActivity = System.currentTimeMillis()
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
                AppLogger.log(
                    "Path3",
                    "UDP ASSOCIATE raw packet len=${packet.length} from=${packet.socketAddress} bytes=${packet.data.copyOfRange(0, minOf(packet.length, 16)).joinToString(",") { (it.toInt() and 0xFF).toString() }}",
                )
                val decoded = decodeSocksUdp(packet.data, packet.length)
                if (decoded == null) {
                    AppLogger.log("Path3", "UDP ASSOCIATE decode FAILED for that packet")
                    continue
                }
                val fromAddr = packet.socketAddress as? InetSocketAddress ?: continue

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
                    val network = pickBestNetwork() ?: continue // both paths down - drop, same as before
                    AppLogger.log(
                        "Path3",
                        "UDP ASSOCIATE pin chosen=${path3Router.describeNetwork(network)} wifiWeight=$wifiWeight cellularWeight=$cellularWeight dest=${decoded.host}:${decoded.port}",
                    )
                    val socket = bindPinnedSocket(network) ?: continue
                    pinnedSocket = socket
                    pinnedNetwork = network
                    activeRelays[relayId]?.network = network
                    receiverJob = startPinnedReceiver(socket, decoded.host, decoded.port, fromAddr)
                }
                val socket = pinnedSocket ?: continue
                runCatching {
                    val resolvedAddr = pinnedNetwork?.getAllByName(decoded.host)?.firstOrNull()
                        ?: InetAddress.getByName(decoded.host)
                    val dest = InetSocketAddress(resolvedAddr, decoded.port)
                    socket.send(DatagramPacket(decoded.payload, decoded.payload.size, dest))
                }
                TrafficStats.recordBytes(decoded.payload.size)
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
        val addr = InetAddress.getByName(host)
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

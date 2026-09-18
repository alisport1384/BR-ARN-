package com.bigrocket.experiment

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Independent, standalone test. Does NOT touch BigRocketVpnService, Path3Router,
 * TunPacketRouter, BondingSocksServer, or any existing bonding/VPN file.
 *
 * Purpose: answer one narrow, falsifiable question with a real device and a real
 * remote server - not theory:
 *
 *   Wi-Fi Network  -> Socket A -bindSocket-> api.ipify.org:80 -> what public IP does
 *                                                                 the server report?
 *   Cellular Network -> Socket B -bindSocket-> api.ipify.org:80 -> what public IP does
 *                                                                 the server report?
 *
 * Both sockets run at the same time, each explicitly bound to its own Network via
 * Network.bindSocket() (the same primitive BondingSocksServer/TcpRelayEngine already
 * use for real traffic - this test does not invent a new mechanism, it just isolates
 * and instruments the existing one).
 *
 * Launch directly (no menu/manifest wiring into MainActivity needed):
 *   adb shell am start -n com.bigrocket/com.bigrocket.experiment.NetworkBindTestActivity
 *
 * Requires Wi-Fi connected AND mobile data enabled at the same time (same requirement
 * BigRocket's own bonding already has).
 */
class NetworkBindTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TestScreen(applicationContext)
                }
            }
        }
    }
}

private data class SocketProbeResult(
    val label: String,
    val networkId: String?,
    val requestedTransport: String,
    val networkAcquired: Boolean,
    val localAddress: String?,
    val connectSucceeded: Boolean,
    val publicIp: String?,
    val error: String?,
)

@Composable
private fun TestScreen(context: Context) {
    var log by remember { mutableStateOf("VPN/فیلترشکن رو خاموش کن (تا مسیر مستقیم باشه)، مطمئن شو وای‌فای و دیتای موبایل هر دو روشنن، بعد دکمه رو بزن.") }
    var running by remember { mutableStateOf(false) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Network.bindSocket() Independent Test", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !running,
            onClick = {
                running = true
                log = "در حال اجرا...\n"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runTest(context) { line -> log += line + "\n" } }
                    log += "\n" + result
                    running = false
                }
            },
        ) {
            Text(if (running) "در حال اجرا..." else "Run Test")
        }
        Text(log, modifier = Modifier.padding(top = 16.dp))
    }
}

private suspend fun runTest(context: Context, onProgress: (String) -> Unit): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    onProgress("Wi-Fi و Cellular Network رو درخواست می‌کنیم...")
    val wifiNetwork = withTimeoutOrNull(8_000) { requestNetworkOfType(cm, NetworkCapabilities.TRANSPORT_WIFI) }
    val cellularNetwork = withTimeoutOrNull(8_000) { requestNetworkOfType(cm, NetworkCapabilities.TRANSPORT_CELLULAR) }
    onProgress("Wi-Fi Network: ${wifiNetwork?.toString() ?: "پیدا نشد (وای‌فای وصل نیست؟)"}")
    onProgress("Cellular Network: ${cellularNetwork?.toString() ?: "پیدا نشد (دیتای موبایل خاموشه؟)"}")

    val (a, b) = kotlinx.coroutines.coroutineScope {
        val resultA = async(Dispatchers.IO) {
            probe("Socket A (Wi-Fi)", wifiNetwork, "TRANSPORT_WIFI")
        }
        val resultB = async(Dispatchers.IO) {
            probe("Socket B (Cellular)", cellularNetwork, "TRANSPORT_CELLULAR")
        }
        awaitAll(resultA, resultB)
    }

    return buildString {
        appendLine("===== نتیجه =====")
        listOf(a, b).forEach { r ->
            appendResult(r)
        }
        if (a.publicIp != null && b.publicIp != null) {
            appendLine()
            appendLine(
                if (a.publicIp == b.publicIp) {
                    "⚠️ دو IP عمومی یکسان دیدن — یعنی این دو مسیر واقعاً از دو خروجی متفاوت به اینترنت نمی‌رسیدن " +
                        "(یا وای‌فای/سلولار پشت همون NAT/زیرساخت مشترکن، یا bindSocket واقعاً اثر نکرده)."
                } else {
                    "✅ دو IP عمومی متفاوت — Wi-Fi=${a.publicIp}  Cellular=${b.publicIp} — هر مسیر واقعاً از NAT جدای خودش خارج شده."
                },
            )
        }
    }
}

private fun StringBuilder.appendResult(r: SocketProbeResult) {
    appendLine("--- ${r.label} (${r.requestedTransport}) ---")
    appendLine("  Network ID: ${r.networkId ?: "N/A"}  (acquired=${r.networkAcquired})")
    appendLine("  Local address: ${r.localAddress ?: "N/A"}")
    appendLine("  Connect succeeded: ${r.connectSucceeded}")
    appendLine("  Public IP: ${r.publicIp ?: "N/A"}")
    if (r.error != null) appendLine("  Error: ${r.error}")
}

private fun probe(label: String, network: Network?, requestedTransport: String): SocketProbeResult {
    if (network == null) {
        return SocketProbeResult(label, null, requestedTransport, false, null, false, null, "Network not acquired")
    }
    var localAddress: String? = null
    var socket: Socket? = null
    return try {
        // Resolve DNS through this specific Network too (Section: DNS must follow the
        // same path being tested, otherwise a shared default-network resolver could
        // mask a real per-path difference).
        val addr = network.getByName("api.ipify.org")
        socket = Socket()
        network.bindSocket(socket)
        socket.connect(InetSocketAddress(addr, 80), 8_000)
        localAddress = "${socket.localAddress.hostAddress}:${socket.localPort}"

        socket.getOutputStream().apply {
            write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val allLines = reader.readText()
        val body = allLines.substringAfter("\r\n\r\n").trim()

        SocketProbeResult(label, network.toString(), requestedTransport, true, localAddress, true, body, null)
    } catch (e: Exception) {
        SocketProbeResult(label, network.toString(), requestedTransport, true, localAddress, false, null, "${e.javaClass.simpleName}: ${e.message}")
    } finally {
        try { socket?.close() } catch (_: Exception) {}
    }
}

private suspend fun requestNetworkOfType(cm: ConnectivityManager, transport: Int): Network? =
    suspendCancellableCoroutine { cont ->
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(transport)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
                try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
            }
            override fun onUnavailable() {
                if (cont.isActive) cont.resume(null)
            }
        }
        try {
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

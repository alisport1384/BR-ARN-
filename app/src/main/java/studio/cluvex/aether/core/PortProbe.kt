package studio.cluvex.aether.core

import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The ground-truth "are we connected?" check — identical in spirit to the
 * desktop app: a successful TCP connect to the local SOCKS5 port means the
 * engine is up and tunnelling.
 */
object PortProbe {
    fun isOpen(host: String, port: Int, timeoutMs: Int = 800): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Polls until the port opens or [totalTimeoutMs] elapses. Aborts early if
     * [isEngineAlive] returns false, so a dead engine fails fast with a clear
     * error instead of the caller hanging for the entire (possibly 5-minute)
     * timeout window.
     */
    /**
     * FIX for WireGuard/Gool: Gool establishes two sequential WireGuard tunnels
     * before SOCKS5 listener is created, so it legitimately takes longer than
     * MASQUE. The adaptive backoff is kept, but fast phase is extended to 15s
     * for WG/Gool to avoid missing the port opening during handshake validation.
     * Also ensures isEngineAlive is checked after isOpen to avoid race where
     * port opens just as engine dies.
     */
    suspend fun awaitOpen(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        intervalMs: Long = 300,
        isEngineAlive: () -> Boolean = { true },
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        var interval = intervalMs
        // Extended fast phase for WG/Gool double-tunnel establishment
        val fastPhaseEnd = System.currentTimeMillis() + FAST_PHASE_MS
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            if (isOpen(host, port)) return true
            // Check engine alive AFTER port check to avoid race
            if (!isEngineAlive()) {
                // Give a tiny grace: port might have opened in same instant engine died
                if (!isOpen(host, port)) return false
                return true
            }
            delay(interval)
            if (System.currentTimeMillis() > fastPhaseEnd && interval < MAX_INTERVAL_MS) {
                interval = (interval * 3 / 2).coerceAtMost(MAX_INTERVAL_MS)
            }
        }
        return false
    }

    /**
     * Waits until nothing is listening on [port] any more.
     *
     * 1.2.2 PROTOCOL-SWITCH FIX: a new engine must never be started while the
     * previous one still owns the local SOCKS5 port, otherwise the connect
     * either races a dying listener or verifies against it. Polls at a very
     * cheap 100 ms because a released localhost port is what the user is
     * waiting for.
     */
    suspend fun awaitClosed(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        intervalMs: Long = 100,
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isOpen(host, port, timeoutMs = 250)) return true
            delay(intervalMs)
        }
        return !isOpen(host, port, timeoutMs = 250)
    }

    /** Keep polling tightly for this long, then back off. Extended for Gool/WG double tunnel. */
    private const val FAST_PHASE_MS = 15_000L

    /** Upper bound for the adaptive poll interval. */
    private const val MAX_INTERVAL_MS = 1_500L
}

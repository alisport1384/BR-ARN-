package studio.cluvex.aether.core

import android.content.Context
import java.io.File

/**
 * Writes hev-socks5-tunnel's YAML config to a private file and returns its path.
 *
 * Schema is heiher/hev-socks5-tunnel's own stable, documented format (tunnel/socks5/misc
 * sections) - not something invented here. `tunnel.name`/`tunnel.mtu` only matter for hev's
 * OWN internal lwIP netif; the real TUN device (and its real MTU) is BigRocketVpnService's
 * VpnService.Builder, whose fd is handed to TProxyStartService separately.
 */
object HevConfig {
    fun write(context: Context, mtu: Int): String {
        val yaml = """
            tunnel:
              mtu: $mtu
              multi-queue: false
              name: hev-tun0
              ipv4: 10.0.0.2/24

            socks5:
              port: ${TunnelConfig.SOCKS_PORT}
              address: '${TunnelConfig.SOCKS_HOST}'
              udp: 'udp'

            misc:
              task-stack-size: 20480
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
        """.trimIndent()

        val file = File(context.filesDir, "hev-tunnel.yaml")
        file.writeText(yaml)
        return file.absolutePath
    }
}

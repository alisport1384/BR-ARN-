package com.bigrocket.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigrocket.service.BigRocketVpnService

/**
 * Section 259/273: on-demand entry point for the Virtual Bonding sandbox.
 *
 * Purely additive - does not touch MainActivity, BigRocketVpnService's real
 * traffic path, or TunPacketRouter. Reads the currently running
 * BigRocketVpnService instance (if any) only for protect()/bindSocket() and
 * the already-discovered Wi-Fi/Cellular Networks; VirtualBondingSandbox itself
 * only ever talks to an in-memory VirtualPath3Endpoint and a loopback-UDP VPS
 * simulation (see docs/VirtualBondingPath3.md) - it never touches the real
 * TUN file descriptor or real user traffic.
 *
 * Requires BigRocket's VPN to already be running (Start Connection first),
 * since it needs a live VpnService for protect()/bindSocket() and needs at
 * least one discovered physical Network to bond over.
 */
class VirtualBondingSandboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vpnService = BigRocketVpnService.runningInstance
                    if (vpnService == null) {
                        NoVpnRunningNotice()
                    } else {
                        VirtualBondingDebugPanel(
                            vpnService = vpnService,
                            wifiNetwork = vpnService.sandboxWifiNetwork(),
                            cellularNetwork = vpnService.sandboxCellularNetwork(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoVpnRunningNotice() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "BigRocket VPN در حال اجرا نیست.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "برای اجرای Virtual Bonding Sandbox، اول یک اتصال BigRocket فعال کنید " +
                "(برای protect()/bindSocket() و شبکه‌های Wi-Fi/Cellular کشف‌شده لازم است)، " +
                "بعد دوباره این صفحه را باز کنید.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

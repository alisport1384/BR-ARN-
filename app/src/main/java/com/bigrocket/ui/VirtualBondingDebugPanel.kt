package com.bigrocket.ui

import android.net.Network
import android.net.VpnService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigrocket.bonding.path3.Path3State
import com.bigrocket.bonding.sandbox.BondingSandbox
import com.bigrocket.bonding.sandbox.VirtualBondingSandbox
import com.bigrocket.bonding.sandbox.VirtualBondingSandboxReport
import kotlinx.coroutines.launch

/**
 * Section 124/152: Observability Contract + Debug UI
 *
 * Provides UI to run Virtual Bonding Sandbox on demand.
 * This is intentionally additive (spec Section 259/273) - does not touch existing screens.
 * To be integrated into existing debug screen or MainActivity via explicit call.
 */
@Composable
fun VirtualBondingDebugPanel(
    vpnService: VpnService,
    wifiNetwork: Network?,
    cellularNetwork: Network?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var lastReport by remember { mutableStateOf<VirtualBondingSandboxReport?>(null) }
    var legacyReport by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Idle - Ready to test Virtual Bonding Path3") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Virtual Bonding Path-3 Lab",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "P1=Wi-Fi + P2=Cellular -> Bonding Engine -> VPS (inside sandbox) -> Path3 -> Android",
            style = MaterialTheme.typography.bodySmall
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Networks: wifi=${wifiNetwork?.hashCode() ?: "null"} cellular=${cellularNetwork?.hashCode() ?: "null"}")
                Text("Path3 State: ${lastReport?.path3State ?: Path3State.CREATED}")
                Text("Bonding State: ${lastReport?.bondingState ?: Path3State.CREATED}")
                Text("Status: $statusText")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (wifiNetwork == null || cellularNetwork == null) {
                        statusText = "Need both Wi-Fi and Cellular networks (or at least one for PoC)"
                        return@Button
                    }
                    if (isRunning) return@Button
                    isRunning = true
                    statusText = "Running Virtual Bonding Sandbox with VPS inside..."
                    scope.launch {
                        try {
                            val report = VirtualBondingSandbox.run(
                                vpnService = vpnService,
                                wifiNetwork = wifiNetwork,
                                cellularNetwork = cellularNetwork
                            )
                            lastReport = report
                            statusText = if (report.success) "PASS - ${report.elapsedMs}ms" else "FAIL - ${report.elapsedMs}ms"
                        } catch (e: Exception) {
                            statusText = "Error: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning
            ) {
                Text(if (isRunning) "Running..." else "Run Virtual Bonding Test")
            }

            Button(
                onClick = {
                    if (wifiNetwork == null || cellularNetwork == null) {
                        statusText = "Need networks for legacy test"
                        return@Button
                    }
                    if (isRunning) return@Button
                    isRunning = true
                    statusText = "Running legacy BondingSandbox..."
                    scope.launch {
                        try {
                            val report = BondingSandbox.run(
                                vpnService = vpnService,
                                wifiNetwork = wifiNetwork,
                                cellularNetwork = cellularNetwork
                            )
                            legacyReport = report.summary()
                            statusText = "Legacy: ${if (report.success) "PASS" else "FAIL"}"
                        } catch (e: Exception) {
                            statusText = "Legacy Error: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning
            ) {
                Text("Run Legacy Test")
            }
        }

        lastReport?.let { report ->
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("=== Virtual Bonding Report ===", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(report.summary(), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("=== Proof Matrix ===", style = MaterialTheme.typography.titleSmall)
                    Text(report.proofMatrix.summary(), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("=== Counters ===", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "P3 TX=${report.counters.p3TxBytes} RX=${report.counters.p3RxBytes}\n" +
                                "P1 TX=${report.counters.p1TxBytes} RX=${report.counters.p1RxBytes}\n" +
                                "P2 TX=${report.counters.p2TxBytes} RX=${report.counters.p2RxBytes}\n" +
                                "Bonded TX=${report.counters.bondedTxPackets} RX=${report.counters.bondedRxPackets}\n" +
                                "VPS recv=${report.vpsStats.packetsReceived} sent=${report.vpsStats.packetsSent}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        legacyReport?.let { txt ->
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("=== Legacy BondingSandbox ===", style = MaterialTheme.typography.titleSmall)
                    Text(txt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

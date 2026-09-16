package com.bigrocket.bonding.virtual

import com.bigrocket.bonding.core.BondingConfig

/**
 * Section 18/80/99/100: VPS Position and Local/Remote Endpoint compatibility
 *
 * External VPS = Optional Topology, not inherent requirement
 * Local Virtual Endpoint = Target Architecture
 *
 * This config defines virtual VPS that runs inside sandbox
 */
data class VirtualVpsConfig(
    val bondingConfig: BondingConfig = BondingConfig(),
    val vpsWifiPort: Int = 48_100,
    val vpsCellularPort: Int = 48_101,
    val clientWifiPort: Int = 47_100,
    val clientCellularPort: Int = 47_101,
    val enablePacketEcho: Boolean = true,
    val enableInternetBreakoutSimulation: Boolean = true,
    val simulateVpsLatencyMs: Long = 10,
    val mtu: Int = 1400
)

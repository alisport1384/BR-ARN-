package com.bigrocket.bonding.path3

/**
 * Section 13/69/152/154/178: Path3Endpoint Contract
 *
 * Path3 must be a bidirectional packet interface:
 * Android ──packet──► Path3 ──► Bonding Engine
 * Android ◄─packet── Path3 ◄── Bonding Engine
 *
 * Requirements:
 * - packet without unnecessary modification
 * - preserve direction
 * - independent read/write
 * - explicit lifecycle
 * - manageable with shutdown/restart
 * - no crash/loop on failure
 *
 * IMPORTANT: Socket alone is NOT Path3. Path3 must be network-capable.
 * In Android non-root context, real implementation is TUN via VpnService.
 * For sandbox PoC, VirtualPath3Endpoint simulates same contract with bounded queues.
 */
interface Path3Endpoint {
    fun start()
    fun stop()
    fun state(): Path3State
    fun isActive(): Boolean

    /**
     * Section 214: Outbound direction
     * Android Network Stack -> Path3 -> Bonding Engine
     * Returns true if packet accepted.
     */
    fun injectFromAndroid(packet: ByteArray): Boolean

    /**
     * Poll for packet that arrived from Android side, to be consumed by Bonding Engine.
     * Non-blocking. Returns null if none.
     */
    fun receiveFromAndroid(): ByteArray?

    /**
     * Section 214: Inbound direction
     * Bonding Engine -> Path3 -> Android Network Stack
     * Bonding delivers reassembled packet to be injected to Android.
     */
    fun sendToAndroid(packet: ByteArray): Boolean

    /**
     * Poll for packet that Bonding Engine delivered, to be consumed by Android.
     * Non-blocking.
     */
    fun readForAndroid(): ByteArray?

    val mtu: Int
    val counters: Path3Counters

    /**
     * Section 121/148/122: Backpressure contract - bounded queues
     */
    fun isInputQueueFull(): Boolean
    fun isOutputQueueFull(): Boolean
}

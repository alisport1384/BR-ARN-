package com.bigrocket.bonding.transport

import com.bigrocket.bonding.frame.Frame

/** Section 212/337 (merged): a physical-path adapter never contains scheduling or bonding
 *  decisions (Section 256 "نباید Scheduler داشته باشد / Weight تعیین کند / تصمیم Bonding بگیرد")
 *  - it only knows how to move [Frame]s over one specific physical network and report its own
 *  health. Wi-Fi and Cellular implementations are fully independent of each other (Section 257). */
interface PathAdapter {
    val id: PathId

    fun connect()
    fun disconnect()

    /** Throws on a hard send failure (socket closed, network gone) so the caller
     *  ([com.bigrocket.bonding.transport.PathRuntimeImpl]) can react (Section 410: Transport
     *  Failure -> Path State Update); does not throw for ordinary transient conditions like a
     *  full send buffer, which the caller treats as backpressure instead. */
    fun send(frame: Frame)

    /** Non-blocking-ish single-frame receive; returns null if nothing has arrived within the
     *  adapter's own short poll interval. Never throws on a decode failure - a malformed
     *  datagram is simply skipped (Section 464: Fail Safely) and this returns null for that
     *  poll instead. */
    fun receive(): Frame?

    fun getMetrics(): PathMetrics
    fun isAvailable(): Boolean
}

/** Section 322: the lower-level, callback-based transport contract an adapter is built on top
 *  of. Kept separate from [PathAdapter] because [PathAdapter] is what the scheduler/engine talk
 *  to (frame-oriented, synchronous-style), while [PathTransport] is what a concrete adapter
 *  implementation talks to underneath (socket-oriented, callback-driven) - Section 213/214's
 *  Sender/Receiver pipelines sit on either side of this seam. */
interface PathTransport {
    fun send(frame: Frame)
    fun receive(callback: (Frame) -> Unit)
    fun close()
}

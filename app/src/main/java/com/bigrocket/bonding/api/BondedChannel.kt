package com.bigrocket.bonding.api

import com.bigrocket.bonding.core.BondingState
import com.bigrocket.bonding.transport.PathId
import com.bigrocket.bonding.transport.PathMetrics
import com.bigrocket.bonding.transport.PathState

/** Section 206/26: "این تنها API عمومی است" - the only thing anything outside the `bonding`
 *  package should ever call. It never exposes a [PathAdapter][com.bigrocket.bonding.transport.PathAdapter],
 *  the scheduler, or which physical network anything is using (Section 26 "بالاترین لایه فقط این
 *  را می‌بیند: BondedChannel نه: WiFi Cellular Scheduler Queue"). */
interface BondedChannel {
    fun open()
    fun close()

    /** Splits [data] into frames and dispatches them across every currently-eligible path
     *  (Section 50: Send Data Flow). Returns immediately; frames are queued and sent
     *  asynchronously by each path's own sender worker. */
    fun send(data: ByteArray)

    /** Section 51: non-blocking pull of the next chunk of already-reassembled, in-order bytes
     *  that arrived across either path. Returns null if nothing new is ready yet - this is a
     *  poll, not a blocking read, so callers on a UI/coroutine loop can call it on a timer or
     *  select-loop without risking a stall. */
    fun receive(): ByteArray?

    fun status(): BondingStatus
}

/** Section 196/23: read-only runtime snapshot for observability - deliberately cannot be used to
 *  change engine behavior (Section 825/monitoring layer: "فقط Read Only"). */
data class BondingStatus(
    val engineState: BondingState,
    val paths: List<PathStatus>,
    val framesSent: Long,
    val framesDelivered: Long,
    val framesDroppedTooOld: Long,
    val framesDroppedTooFarAhead: Long,
    val framesPermanentlyLost: Long,
    val framesDroppedNoPath: Long,
    val recoveryRequested: Long,
    val recoveryFulfilled: Long,
)

data class PathStatus(
    val id: PathId,
    val state: PathState,
    val weightPercent: Int,
    val metrics: PathMetrics,
    val queueDepth: Int,
    val queueDropped: Long,
)

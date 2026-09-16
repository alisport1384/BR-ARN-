package com.bigrocket.bonding.transport

import com.bigrocket.bonding.buffer.BoundedSendQueue
import com.bigrocket.bonding.buffer.SendQueue
import com.bigrocket.bonding.frame.Frame

/** Section 323. */
interface PathRuntime {
    val id: PathId
    val state: PathState
    val metrics: PathMetrics
    fun start()
    fun stop()
}

/** Section 211/355: the per-path container the scheduler and queue layer actually operate on -
 *  bundles one [PathAdapter] with its own independent [SendQueue] (Section 309/355 "هر مسیر
 *  Queue مستقل دارد") and enforces the [PathState] machine (Section 409/462) so a caller can
 *  never push it through an illegal transition (e.g. FAILED directly to ACTIVE, Section 461). */
class PathRuntimeImpl(
    override val id: PathId,
    val adapter: PathAdapter,
    sendQueueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : PathRuntime {

    val sendQueue: SendQueue = BoundedSendQueue(sendQueueCapacity)

    @Volatile private var currentState: PathState = PathState.INIT
    @Volatile private var currentMetrics: PathMetrics = PathMetrics.initial()

    override val state: PathState get() = currentState
    override val metrics: PathMetrics get() = currentMetrics

    override fun start() {
        adapter.connect()
        transitionTo(PathState.ACTIVE)
    }

    override fun stop() {
        adapter.disconnect()
        transitionTo(PathState.CLOSED)
    }

    fun updateMetrics(newMetrics: PathMetrics) {
        currentMetrics = newMetrics
    }

    /** Section 462: rejects (logs and ignores, does not throw - a bad transition attempt is a
     *  logic bug worth surfacing via diagnostics, not a reason to crash the runtime, Section
     *  412/453) any transition [PathState] itself doesn't allow. */
    fun transitionTo(next: PathState): Boolean {
        val current = currentState
        if (!current.canTransitionTo(next)) return false
        currentState = next
        return true
    }

    /** Section 213: hands one frame to this path's queue rather than sending it directly - the
     *  actual transmit happens on this path's own sender loop, which is what keeps "هیچ Path
     *  نباید دیگری را Block کند" (Section 10) true even when the adapter's underlying send()
     *  call blocks. */
    fun enqueue(frame: Frame): Boolean = sendQueue.offer(frame)

    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 2048
    }
}

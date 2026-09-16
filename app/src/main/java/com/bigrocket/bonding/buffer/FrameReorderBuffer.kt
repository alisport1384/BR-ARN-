package com.bigrocket.bonding.buffer

import com.bigrocket.bonding.frame.Frame
import java.util.concurrent.ConcurrentSkipListMap

/** Section 327/238. Owns exactly one stream's delivery order (Section 46: "Reassembler نباید
 *  شبکه را بشناسد" - it never sees a path, only sequence numbers). */
interface FrameReorderBuffer {
    /** Section 80/238: insert one received (already validated) frame. */
    fun push(frame: Frame)

    /** Section 37/128: returns every frame that has now become deliverable, in order, and
     *  removes them from the buffer. May return an empty list (nothing new is ready yet). */
    fun readOrdered(): List<Frame>
}

/**
 * Section 81 (Reorder Window) + Section 239-241 (Delivery Window / Reorder Timeout / Recovery
 * Decision Matrix), combined into one class:
 *
 *  - Frame ahead of [expectedSequence] but within [windowSize]: buffered (Section 81 "Frame
 *    Ahead: Store").
 *  - Frame == [expectedSequence]: delivered immediately, then any consecutive already-buffered
 *    frames cascade-deliver too (Section 284's worked example).
 *  - Frame older than [expectedSequence] (duplicate/already delivered): dropped (Section 430).
 *  - Frame further ahead than [windowSize]: dropped rather than grown without bound (Section
 *    161: "ممنوع: Unlimited Queue").
 *  - If the frame at [expectedSequence] hasn't arrived within [softTimeoutMs] of the *oldest*
 *    later frame arriving, [onMissingSequence] fires once (Section 240: "Timer Start | Wait |
 *    Recovery Decision") so a [com.bigrocket.bonding.recovery.RecoveryManager] can request a
 *    resend. If it still hasn't arrived after [hardTimeoutMs], the gap is given up on for good -
 *    [expectedSequence] jumps forward to the next frame that actually did arrive, and that
 *    frame (plus any that cascade after it) is delivered with the gap simply skipped (Section
 *    19 Mode 1 "Missing Frame -> Drop -> Continue" - the fallback every mode eventually reaches
 *    if recovery can't succeed in time, since waiting forever is not an option, Section 372).
 */
class WindowedReorderBuffer(
    private val windowSize: Int,
    private val softTimeoutMs: Long,
    private val hardTimeoutMs: Long,
    startSequence: Long = 0,
    private val onMissingSequence: (missingSequence: Long) -> Unit = {},
) : FrameReorderBuffer {

    private val lock = Any()
    private val buffered = ConcurrentSkipListMap<Long, Frame>()

    @Volatile private var expectedSequence: Long = startSequence
    @Volatile private var gapFirstObservedAtMs: Long = 0L
    @Volatile private var softTimeoutFired: Boolean = false

    @Volatile var droppedTooOldCount: Long = 0
        private set

    @Volatile var droppedTooFarAheadCount: Long = 0
        private set

    @Volatile var permanentlyLostCount: Long = 0
        private set

    override fun push(frame: Frame) {
        synchronized<Unit>(lock) {
            val seq = frame.header.sequence
            when {
                seq < expectedSequence -> droppedTooOldCount++ // Section 430: duplicate/late
                seq >= expectedSequence + windowSize -> droppedTooFarAheadCount++ // Section 161
                seq == expectedSequence -> {
                    buffered[seq] = frame
                    // The frame we were waiting on just showed up - the gap (if any) is over.
                    gapFirstObservedAtMs = 0L
                    softTimeoutFired = false
                }
                else -> {
                    buffered.putIfAbsent(seq, frame)
                    if (gapFirstObservedAtMs == 0L) gapFirstObservedAtMs = now()
                }
            }
        }
    }

    override fun readOrdered(): List<Frame> = synchronized(lock) {
        checkTimeout()

        val ready = ArrayList<Frame>()
        while (true) {
            val head = buffered[expectedSequence] ?: break
            buffered.remove(expectedSequence)
            ready.add(head)
            expectedSequence++
        }
        ready
    }

    /** Section 240: called on every [readOrdered] poll, not just once, so a still-missing gap
     *  is re-evaluated against the clock on every pass rather than only at insertion time. */
    private fun checkTimeout() {
        val firstObserved = gapFirstObservedAtMs
        if (firstObserved == 0L) return // no gap currently open

        val elapsed = now() - firstObserved
        if (!softTimeoutFired && elapsed >= softTimeoutMs) {
            softTimeoutFired = true
            onMissingSequence(expectedSequence)
        }
        if (elapsed >= hardTimeoutMs) {
            val nextAvailable = buffered.ceilingKey(expectedSequence)
            if (nextAvailable != null) {
                val skipped = nextAvailable - expectedSequence
                permanentlyLostCount += skipped
                expectedSequence = nextAvailable
            }
            // If nothing at all is buffered yet, there is nothing to jump to - keep waiting;
            // the gap will simply re-arm from the next arrival.
            gapFirstObservedAtMs = 0L
            softTimeoutFired = false
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L
}

package com.bigrocket.bonding.recovery

import com.bigrocket.bonding.frame.Frame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** Section 328/216. "Recovery نباید Scheduler را تغییر دهد / مسیر انتخاب کند" (Section 216) -
 *  this class only ever asks "is sequence X missing, and can I get it resent", never touches
 *  path selection. */
interface RecoveryManager {
    fun detectLoss(streamId: Int): List<Long>
    fun request(sequence: Long)
}

/** Section 371 (Sender's outgoing frames need to survive briefly in case they're asked for
 *  again): a bounded, time-and-count-limited cache of recently transmitted DATA frames, keyed by
 *  sequence, that [SelectiveRecoveryManager.request] draws from to satisfy a resend. Owned by
 *  whichever side of the engine calls [record] (the sending side) - this is a plain data
 *  structure, not a network endpoint, matching Section 216's "Recovery نباید ... " boundary: the
 *  cache itself makes no decisions either. */
class RetransmitCache(
    private val capacity: Int = 512,
    private val ttlMs: Long = 5_000,
) {
    private data class Entry(val frame: Frame, val recordedAtMs: Long)

    private val bySequence = ConcurrentHashMap<Long, Entry>()
    private val insertionOrder = ConcurrentLinkedDeque<Long>()

    fun record(frame: Frame) {
        val seq = frame.header.sequence
        bySequence[seq] = Entry(frame, now())
        insertionOrder.addLast(seq)
        while (insertionOrder.size > capacity) {
            val evicted = insertionOrder.pollFirst() ?: break
            bySequence.remove(evicted)
        }
    }

    /** Returns null if the frame was never recorded, has already been evicted for space, or has
     *  aged out past [ttlMs] (Section 240: a retransmit request that arrives too late to matter
     *  gets the same "give up" treatment as a frame that never arrives at all). */
    fun get(sequence: Long): Frame? {
        val entry = bySequence[sequence] ?: return null
        if (now() - entry.recordedAtMs > ttlMs) {
            bySequence.remove(sequence)
            return null
        }
        return entry.frame
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Section 19 Mode 2 (Selective Recovery: "Missing Sequence -> Request Again"), implemented as a
 * direct, same-process hookup between the receiving side's gap detector
 * ([com.bigrocket.bonding.buffer.WindowedReorderBuffer]'s `onMissingSequence` callback) and the
 * sending side's [RetransmitCache].
 *
 * HONESTY NOTE (do not remove): a real, cross-device deployment would carry the request as an
 * actual RECOVERY_REQUEST wire frame (Section 144, already fully modeled in [com.bigrocket.bonding.frame.MessageType]
 * and [com.bigrocket.bonding.frame.FrameFlags.RECOVERY]) sent back over one of the physical
 * paths, and the far side would reply with RECOVERY_DATA. [com.bigrocket.bonding.sandbox.BondingSandbox]
 * runs both the "sender" and the "destination" ends inside the same process today (Section
 * 208/295-297: no compatible remote endpoint exists yet), so this class calls straight into the
 * sender's cache instead of round-tripping a request frame through a socket to itself - the gap
 * detection, request/resend decision, and end-to-end delivery-after-recovery behavior this
 * exercises are exactly the same either way; only the transport of the *request itself* is
 * simplified. Swapping [onResend] for one that actually sends a RECOVERY_REQUEST frame is the
 * only change needed to make this cross-process.
 */
class SelectiveRecoveryManager(
    private val cache: RetransmitCache,
    /** Called with the recovered frame so the caller can re-inject it wherever incoming frames
     *  normally arrive (Section 145: RECOVERY_DATA is delivered like any other frame, just
     *  flagged - see [com.bigrocket.bonding.frame.FrameFlags.RECOVERY]). Returns nothing; if the
     *  sequence was never recorded or already aged out, nothing is delivered and the gap is
     *  left for [WindowedReorderBuffer]'s own hard timeout to eventually give up on. */
    private val onResend: (Frame) -> Unit,
) : RecoveryManager {

    @Volatile private var requestedCount: Long = 0
    @Volatile private var fulfilledCount: Long = 0

    val stats: RecoveryStats get() = RecoveryStats(requestedCount, fulfilledCount)

    override fun detectLoss(streamId: Int): List<Long> =
        emptyList() // Gap detection lives in WindowedReorderBuffer (Section 46 boundary); this
                    // method exists to satisfy the Section 328 contract for callers that want to
                    // poll rather than be called back, but this implementation is callback-driven.

    override fun request(sequence: Long) {
        requestedCount++
        val frame = cache.get(sequence) ?: return
        fulfilledCount++
        val recoveredFrame = frame.copy(
            header = frame.header.copy(flags = frame.header.flags or com.bigrocket.bonding.frame.FrameFlags.RECOVERY),
        )
        onResend(recoveredFrame)
    }
}

data class RecoveryStats(val requested: Long, val fulfilled: Long)

package com.bigrocket.bonding.buffer

import com.bigrocket.bonding.frame.Frame
import com.bigrocket.bonding.frame.FrameFlags

/** Section 326. */
interface SendQueue {
    /** Returns false if the frame was dropped instead of queued (Section 237: overflow policy). */
    fun offer(frame: Frame): Boolean
    fun poll(): Frame?
    fun size(): Int
}

/** Section 326/236/237: a fixed-capacity, priority-aware queue - "ممنوع: Unlimited Queue"
 *  (Section 161). On overflow it first tries to make room by dropping the oldest currently
 *  queued LOW-priority (Section 62: no [FrameFlags.PRIORITY] bit, not [FrameFlags.CONTROL])
 *  frame; if none exists, the *incoming* frame is dropped instead - control and priority frames
 *  are never silently discarded to make room for an ordinary DATA frame. Every drop is counted
 *  in [droppedCount] for [com.bigrocket.bonding.transport.PathMetrics]/diagnostics (Section 197). */
class BoundedSendQueue(private val capacity: Int) : SendQueue {
    private val lock = Any()
    private val deque = ArrayDeque<Frame>()

    @Volatile var droppedCount: Long = 0
        private set

    override fun offer(frame: Frame): Boolean = synchronized(lock) {
        if (deque.size < capacity) {
            deque.addLast(frame)
            return true
        }

        val lowPriorityIndex = deque.indexOfFirst {
            !FrameFlags.has(it.header.flags, FrameFlags.CONTROL) &&
                !FrameFlags.has(it.header.flags, FrameFlags.PRIORITY)
        }

        if (lowPriorityIndex >= 0) {
            // Oldest low-priority frame yields its slot to the new arrival - this keeps the
            // queue's *age* distribution sane for ordinary DATA frames (a steady producer would
            // otherwise starve every new low-priority frame forever once full) while still
            // guaranteeing CONTROL/PRIORITY frames always displace a low-priority one first.
            deque.removeAt(lowPriorityIndex)
            droppedCount++
            deque.addLast(frame)
            return true
        }

        // Queue is full of nothing but CONTROL/PRIORITY frames - drop the incoming frame.
        droppedCount++
        return false
    }

    override fun poll(): Frame? = synchronized(lock) { deque.removeFirstOrNull() }

    override fun size(): Int = synchronized(lock) { deque.size }
}

package com.bigrocket.bonding.buffer

import com.bigrocket.bonding.frame.Frame

/** Section 326. Unlike [SendQueue] this sits right after a path's raw receive (Section 3.5/214)
 *  and just before validation/reorder, so it is deliberately dumb - no priority logic here, that
 *  happens once, in [SendQueue], on the way out. */
interface ReceiveQueue {
    fun insert(frame: Frame): Boolean
    fun next(): Frame?
}

/** Section 161: still fixed-capacity ("ممنوع: Unlimited Queue") - if a path's receiver outruns
 *  the reassembly worker draining this queue, new frames are dropped (oldest-first is wrong
 *  here: an old undelivered frame is more likely to still be inside the reorder window than a
 *  brand new one, so dropping incoming keeps whatever has the best chance of still being
 *  useful). */
class BoundedReceiveQueue(private val capacity: Int) : ReceiveQueue {
    private val lock = Any()
    private val deque = ArrayDeque<Frame>()

    @Volatile var droppedCount: Long = 0
        private set

    override fun insert(frame: Frame): Boolean = synchronized(lock) {
        if (deque.size >= capacity) {
            droppedCount++
            return false
        }
        deque.addLast(frame)
        return true
    }

    override fun next(): Frame? = synchronized(lock) { deque.removeFirstOrNull() }
}

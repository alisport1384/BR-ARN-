package com.bigrocket.bonding.frame

/** Section 134 (Flags): a 4-bit field, R|A|P|C. Kept as a full Int in [FrameHeader] (matching
 *  Section 319's `val flags: Int`) but only the low 4 bits are ever set by this engine; the rest
 *  are reserved for future optional flags (Section 153 allows adding fields/bits, never
 *  repurposing existing ones). */
object FrameFlags {
    /** Recovery Required - this frame is standing in for one the sender is being asked to
     *  resend (Section 144/145: RECOVERY_DATA), so the receiver must not re-request it again on
     *  a duplicate arrival. */
    const val RECOVERY: Int = 1 shl 0

    /** ACK Required - Section 146, reliable-mode only. */
    const val ACK_REQUIRED: Int = 1 shl 1

    /** Priority - Section 62/236: schedule ahead of NORMAL/LOW frames on both send and delivery. */
    const val PRIORITY: Int = 1 shl 2

    /** Control - Section 63: belongs to the control plane (HELLO/HEARTBEAT/SYNC/RECOVERY/ACK),
     *  not the data plane, and is exempt from the data stream's sequence/reorder accounting. */
    const val CONTROL: Int = 1 shl 3

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}

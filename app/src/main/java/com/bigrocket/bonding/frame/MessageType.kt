package com.bigrocket.bonding.frame

/** Section 128 (Protocol Packet Types) / Section 321. Every frame carries exactly one of these
 *  as its header type. Ordinal is used as the on-wire byte, so entries must never be reordered
 *  or removed - only appended (Section 153: Protocol Compatibility Rule - "Add Optional Field"
 *  is allowed, "Change Existing Field Meaning" is forbidden, and reordering an enum used as a
 *  wire value would silently do exactly that to every value after the change). */
enum class MessageType {
    DATA,
    HELLO,
    HELLO_ACK,
    HEARTBEAT,
    HEARTBEAT_ACK,
    SYNC,
    ACK,
    RECOVERY_REQUEST,
    RECOVERY_DATA,
    ERROR;

    companion object {
        private val VALUES = entries.toTypedArray()

        /** Returns null for an out-of-range ordinal instead of throwing - Section 444 requires
         *  an unrecognized/unsupported value to be rejected and logged, not crash the decoder. */
        fun fromWire(byte: Int): MessageType? = VALUES.getOrNull(byte)
    }
}

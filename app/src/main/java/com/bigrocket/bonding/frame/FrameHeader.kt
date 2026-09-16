package com.bigrocket.bonding.frame

/** Section 319. Wire layout is defined by [FrameCodec] (Section 129/130); this is the decoded,
 *  in-memory shape. [checksum] covers every other header field plus the payload (Section 135) -
 *  it is part of this data class purely so a decoded frame carries the value it arrived with for
 *  logging/diagnostics (Section 197); [FrameCodec.encode] always recomputes it fresh rather than
 *  trusting a caller-supplied value. */
data class FrameHeader(
    val version: Int,
    val type: MessageType,
    val streamId: Int,
    val sequence: Long,
    val timestamp: Long,
    val payloadSize: Int,
    val flags: Int,
    val checksum: Long,
) {
    companion object {
        /** Section 129/153: only version 1 exists today. A decoder that ever sees a higher
         *  version must reject rather than guess at a layout it doesn't know (Section 444). */
        const val CURRENT_VERSION: Int = 1
    }
}

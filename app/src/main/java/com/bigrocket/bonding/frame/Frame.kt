package com.bigrocket.bonding.frame

/** Section 208/320: "Frame نباید بداند مسیر ارسال / Scheduler / Socket" - a Frame carries no
 *  notion of which physical path it travels on or will travel on; [header.streamId]/[header.sequence]
 *  are the only identity it has, and path assignment lives entirely in the scheduler/queue layer.
 *
 * [equals]/[hashCode] are overridden because the default data-class implementation would compare
 * [payload] by reference identity being an Array... actually ByteArray.equals is reference-based
 * by default in a data class unless overridden, which would break duplicate-detection (Section
 * 430) and test assertions that compare frames by value. */
data class Frame(
    val header: FrameHeader,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

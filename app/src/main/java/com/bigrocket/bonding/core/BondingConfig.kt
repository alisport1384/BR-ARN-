package com.bigrocket.bonding.core

/** Section 224/1261 (Configuration Object): every tunable in one place, separate from code
 *  (Section 187/504 "Configuration جدا از Code"). All time values are milliseconds. */
data class BondingConfig(
    /** Section 230: payload bytes per frame before the codec's own hard 64KB ceiling. */
    val maxPayloadSize: Int = 1200,

    /** Section 326/161: fixed capacity per path's outbound queue. */
    val sendQueueCapacity: Int = 2048,

    /** Section 326/161: fixed capacity for the raw pre-reorder receive queue per path. */
    val receiveQueueCapacity: Int = 2048,

    /** Section 81/239: how many sequence numbers ahead of the next-expected one the reorder
     *  buffer will hold before giving up on them as "too far ahead" (Section 161). */
    val reorderWindowSize: Int = 512,

    /** Section 240: how long a gap is tolerated before [recoveryEnabled] triggers a
     *  RECOVERY_REQUEST for it. */
    val reorderSoftTimeoutMs: Long = 150,

    /** Section 240/372: how long a gap is tolerated in total before it is given up on for good
     *  and the reorder buffer skips past it rather than stalling forever. Must be >
     *  [reorderSoftTimeoutMs]. */
    val reorderHardTimeoutMs: Long = 800,

    /** Section 19 Mode 2: whether a missing frame triggers a resend request at all, or the
     *  engine goes straight to Section 19 Mode 1 (drop and continue) on every gap. */
    val recoveryEnabled: Boolean = true,

    /** Section 64: interval between HEARTBEAT frames per path, used to keep [com.bigrocket.bonding.transport.PathMetrics]
     *  fresh (Section 169: "مستمر اما سبک"). */
    val heartbeatIntervalMs: Long = 1_000,

    /** Section 60/232: interval between scheduler weight recomputation cycles - deliberately
     *  decoupled from per-frame path selection (Section 168/354). */
    val metricsUpdateIntervalMs: Long = 1_000,

    /** Section 76: maximum percentage-point change to any path's weight per recompute cycle. */
    val maxWeightStepPerUpdate: Int = 10,

    /** Section 79: hard cap on consecutive frames sent to the same path regardless of weight. */
    val maxConsecutivePicksPerPath: Int = 10,
) {
    init {
        require(reorderHardTimeoutMs > reorderSoftTimeoutMs) {
            "reorderHardTimeoutMs must be greater than reorderSoftTimeoutMs"
        }
        require(maxPayloadSize in 1..60_000) { "maxPayloadSize must fit well under the 64KB frame ceiling" }
    }
}

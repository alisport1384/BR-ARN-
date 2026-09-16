package com.bigrocket.bonding.transport

/** Section 318. A snapshot of one path's observed quality at [timestamp]. */
data class PathMetrics(
    val latencyMs: Long,
    val jitterMs: Long,
    val packetLoss: Double,
    val bandwidthEstimate: Long,
    val timestamp: Long,
) {
    companion object {
        /** Optimistic placeholder used only until the first real heartbeat round-trip completes
         *  (Section 258 "Collect Initial Metrics") - deliberately equal-quality for both paths so
         *  the very first scheduler decision (Section 59 "No Available Path -> Reject / One
         *  Available Path -> 100%% / Multiple Paths -> Weighted Distribution") starts from a fair
         *  50/50 split rather than favoring whichever path happened to report first. */
        fun initial(): PathMetrics = PathMetrics(
            latencyMs = 50L,
            jitterMs = 5L,
            packetLoss = 0.0,
            bandwidthEstimate = 1_000_000L,
            timestamp = System.nanoTime() / 1_000_000L,
        )

        /** Used when a path has failed outright (Section 361: FAILED -> Blocked) - worst-possible
         *  values so it can never win a score comparison while still being a valid, non-null
         *  PathMetrics for code that doesn't want to null-check everywhere. */
        fun failed(): PathMetrics = PathMetrics(
            latencyMs = Long.MAX_VALUE / 2,
            jitterMs = Long.MAX_VALUE / 2,
            packetLoss = 1.0,
            bandwidthEstimate = 0L,
            timestamp = System.nanoTime() / 1_000_000L,
        )
    }
}

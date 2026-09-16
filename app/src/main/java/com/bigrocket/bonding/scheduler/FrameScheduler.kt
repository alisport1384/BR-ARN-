package com.bigrocket.bonding.scheduler

import com.bigrocket.bonding.transport.PathMetrics
import com.bigrocket.bonding.transport.PathRuntime

/** Section 324/342: the scheduler only ever decides which path a frame should go to - Section
 *  15/210 "Scheduler فقط تصمیم می‌گیرد" - it never sends anything itself and never touches
 *  Android/socket APIs (Section 15 "Scheduler مسئول نیست: ارسال Packet / مدیریت Socket /
 *  مدیریت Android Network"). */
interface FrameScheduler {
    /** Section 59: no available path -> null (caller rejects/queues); exactly one available ->
     *  that one, unconditionally; more than one -> a weighted pick per the scheduler's own
     *  algorithm. */
    fun selectPath(paths: List<PathRuntime>): PathRuntime?

    /** Section 196/23 (Monitoring Layer, read-only): current smoothed weight for [pathId], for
     *  status reporting only - never used by [selectPath] itself to make its own decision, that
     *  always reads the scheduler's own internal state directly. Returns 0 for a path the
     *  scheduler has never seen. */
    fun currentWeightPercent(pathId: com.bigrocket.bonding.transport.PathId): Int

    /** Section 60/243: feeds one path's freshly collected metrics in so the next
     *  [selectPath] call reflects it - update and select are deliberately separate calls so a
     *  metrics-collection cycle and a per-frame dispatch cycle can run at different rates
     *  (Section 168/354: "Scheduler نباید برای هر بیت اجرا شود"). */
    fun update(pathId: com.bigrocket.bonding.transport.PathId, metrics: PathMetrics)
}

/** Section 58/344: one path's computed score at a point in time - kept as its own type so the
 *  scoring math is independently testable (Section 472/473) from the weight-normalization and
 *  selection steps built on top of it. */
internal data class PathScore(
    val pathId: com.bigrocket.bonding.transport.PathId,
    val score: Double,
)

package com.bigrocket.bonding.scheduler

import com.bigrocket.bonding.transport.PathId
import com.bigrocket.bonding.transport.PathMetrics
import com.bigrocket.bonding.transport.PathRuntime
import com.bigrocket.bonding.transport.PathState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Section 333: `class AdaptiveScheduler : FrameScheduler`. Implements:
 *
 *  - Path scoring (Section 74/344): additive score from latency/loss/jitter/bandwidth/queue.
 *  - Weight normalization (Section 75): score share -> percentage weight.
 *  - Smooth transition (Section 76/347): weight moves toward its target by at most
 *    [maxWeightStepPerUpdate] points per [update] call, never jumps.
 *  - Recovery ramp (Section 283/363): a path that just became ACTIVE starts capped at 10% and
 *    is allowed to climb by the same step each update, same mechanism as ordinary smoothing.
 *  - Weighted selection (Section 78/948): **deterministic** smooth weighted round-robin (the
 *    algorithm used by, e.g., nginx's upstream load balancer), not random selection. A single
 *    weighted-random sample only converges to the configured ratio over many draws; SWRR hits
 *    the ratio on every cycle of `sum(weights)` draws with no variance, and - just as
 *    importantly - never depends on chance for an individual decision, which for a system whose
 *    whole job is honoring a configured/measured path preference is the correct property to
 *    have, not merely a nice-to-have.
 *  - Burst protection (Section 79): a hard cap on consecutive picks of the same path, kept as a
 *    defense-in-depth backstop even though SWRR's own interleaving already avoids long runs at
 *    any weight ratio short of 100/0.
 *  - Failure-aware eligibility (Section 361): FAILED/CLOSED/INIT paths are never selectable;
 *    ACTIVE/DEGRADED/RECOVERING are, with DEGRADED/RECOVERING naturally scoring lower (and
 *    RECOVERING additionally ramp-capped) rather than being special-cased in [selectPath] itself.
 */
class AdaptiveScheduler(
    private val maxWeightStepPerUpdate: Int = 10,
    private val maxConsecutivePicks: Int = 10,
    private val recoveryStartWeight: Int = 10,
) : FrameScheduler {

    private val latestMetrics = ConcurrentHashMap<PathId, PathMetrics>()
    private val smoothedWeights = ConcurrentHashMap<PathId, Int>()
    private val swrrCurrent = ConcurrentHashMap<PathId, Int>()
    private val lastKnownState = ConcurrentHashMap<PathId, PathState>()

    @Volatile private var lastPickedPathId: PathId? = null
    @Volatile private var consecutivePickCount: Int = 0

    override fun currentWeightPercent(pathId: PathId): Int = smoothedWeights[pathId] ?: 0

    override fun update(pathId: PathId, metrics: PathMetrics) {
        latestMetrics[pathId] = metrics
    }

    override fun selectPath(paths: List<PathRuntime>): PathRuntime? {
        val eligible = paths.filter {
            it.state == PathState.ACTIVE || it.state == PathState.DEGRADED || it.state == PathState.RECOVERING
        }

        detectFreshRecoveries(paths)

        return when (eligible.size) {
            0 -> null // Section 59: No Available Path -> Reject
            1 -> eligible.first().also { recordPick(it.id) } // Section 59: One Available Path -> 100%
            else -> selectAmong(eligible)
        }
    }

    /** Section 283/363: a path that just transitioned into ACTIVE from FAILED/RECOVERING/INIT
     *  gets its smoothed weight reset to [recoveryStartWeight] so it ramps back in gradually
     *  instead of immediately grabbing its full computed share. */
    private fun detectFreshRecoveries(paths: List<PathRuntime>) {
        for (path in paths) {
            val previous = lastKnownState[path.id]
            if (previous != null && previous != PathState.ACTIVE && path.state == PathState.ACTIVE) {
                smoothedWeights[path.id] = recoveryStartWeight
            }
            lastKnownState[path.id] = path.state
        }
    }

    private fun selectAmong(eligible: List<PathRuntime>): PathRuntime {
        recomputeSmoothedWeights(eligible)

        val forcedSwitch = consecutivePickCount >= maxConsecutivePicks
        val candidate = if (forcedSwitch) {
            // Section 79: burst cap hit - force a pick that is NOT the path we've been
            // hammering, choosing the next-highest-weight alternative among the rest.
            val excluded = lastPickedPathId
            eligible.filter { it.id != excluded }
                .maxByOrNull { smoothedWeights[it.id] ?: 0 }
                ?: eligible.first()
        } else {
            swrrPick(eligible)
        }

        recordPick(candidate.id)
        return candidate
    }

    /** Section 78/948: Smooth Weighted Round-Robin - see class doc for why this replaces the
     *  document's illustrative `weightedRandom(scores)` pseudocode with a deterministic
     *  equivalent that still honors the same weights exactly. */
    private fun swrrPick(eligible: List<PathRuntime>): PathRuntime {
        val totalWeight = eligible.sumOf { (smoothedWeights[it.id] ?: 1).coerceAtLeast(1) }
        var best: PathRuntime? = null
        var bestCurrent = Int.MIN_VALUE

        for (path in eligible) {
            val weight = (smoothedWeights[path.id] ?: 1).coerceAtLeast(1)
            val updatedCurrent = (swrrCurrent[path.id] ?: 0) + weight
            swrrCurrent[path.id] = updatedCurrent
            if (updatedCurrent > bestCurrent) {
                bestCurrent = updatedCurrent
                best = path
            }
        }

        val chosen = best ?: eligible.first()
        swrrCurrent[chosen.id] = (swrrCurrent[chosen.id] ?: 0) - totalWeight
        return chosen
    }

    private fun recordPick(pathId: PathId) {
        if (pathId == lastPickedPathId) {
            consecutivePickCount++
        } else {
            lastPickedPathId = pathId
            consecutivePickCount = 1
        }
    }

    /** Section 74-76/344-347: score every eligible path, normalize to a 0-100 share, then move
     *  each path's stored [smoothedWeights] toward that target by at most
     *  [maxWeightStepPerUpdate] - the actual value [swrrPick] uses is always the smoothed one,
     *  never the raw just-computed target. */
    private fun recomputeSmoothedWeights(eligible: List<PathRuntime>) {
        val scores = eligible.map { PathScore(it.id, scoreFor(it)) }
        val totalScore = scores.sumOf { it.score }.coerceAtLeast(MIN_TOTAL_SCORE)

        val targetWeights = scores.associate { it.pathId to ((it.score / totalScore) * 100.0) }
        val rawTargets = normalizeToIntegerPercentages(targetWeights)

        for ((pathId, target) in rawTargets) {
            val current = smoothedWeights[pathId] ?: target // first observation: jump straight there
            val delta = target - current
            val step = delta.coerceIn(-maxWeightStepPerUpdate, maxWeightStepPerUpdate)
            smoothedWeights[pathId] = current + step
        }
    }

    /** Section 74/344: BandwidthScore + LatencyScore + LossScore + StabilityScore + QueueScore.
     *  Each sub-score is inverted where "lower is better" (latency, jitter, queue depth) so a
     *  higher [PathScore.score] always means a more desirable path across all five factors, and
     *  all five are scaled to roughly comparable magnitudes so no single raw unit (bits/sec vs
     *  milliseconds) silently dominates the sum. */
    private fun scoreFor(path: PathRuntime): Double {
        val metrics = latestMetrics[path.id] ?: PathMetrics.initial()
        val queueSize = (path as? com.bigrocket.bonding.transport.PathRuntimeImpl)?.sendQueue?.size() ?: 0

        val bandwidthScore = metrics.bandwidthEstimate / 100_000.0
        val latencyScore = 1000.0 / (metrics.latencyMs.coerceAtLeast(1) + 1)
        val lossScore = (1.0 - metrics.packetLoss.coerceIn(0.0, 1.0)) * 100.0
        val stabilityScore = 500.0 / (metrics.jitterMs.coerceAtLeast(0) + 1)
        val queueScore = 100.0 / (queueSize + 1)

        val degradedPenalty = if (path.state == PathState.DEGRADED) 0.5 else 1.0
        val recoveringPenalty = if (path.state == PathState.RECOVERING) 0.25 else 1.0

        val raw = bandwidthScore + latencyScore + lossScore + stabilityScore + queueScore
        return max(raw * degradedPenalty * recoveringPenalty, MIN_PATH_SCORE)
    }

    /** Turns fractional percentage targets into integers that sum to exactly 100 - simple
     *  largest-remainder rounding so no path's share is silently lost/gained to rounding error
     *  when there are more than two paths. */
    private fun normalizeToIntegerPercentages(targets: Map<PathId, Double>): Map<PathId, Int> {
        if (targets.isEmpty()) return emptyMap()
        val floors = targets.mapValues { it.value.toInt() }
        var remainder = 100 - floors.values.sum()
        val byFractionDesc = targets.entries.sortedByDescending { it.value - it.value.toInt() }
        val result = floors.toMutableMap()
        var i = 0
        while (remainder > 0 && byFractionDesc.isNotEmpty()) {
            val key = byFractionDesc[i % byFractionDesc.size].key
            result[key] = (result[key] ?: 0) + 1
            remainder--
            i++
        }
        return result
    }

    companion object {
        private const val MIN_TOTAL_SCORE = 0.0001
        private const val MIN_PATH_SCORE = 0.0001
    }
}

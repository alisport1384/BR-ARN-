package com.bigrocket.service

import kotlin.math.abs

/**
 * Calculates routing share and the independent identity/IP owner for the two physical paths.
 *
 * User scores are authoritative for routing whenever they differ. Observed quality may only
 * influence the routing split when the user gives both paths the same score.
 *
 * Identity/IP selection is intentionally independent from routing share, and is sticky: once a
 * path owns identity, it keeps it regardless of score/latency until it is the one that actually
 * goes down - at which point the other path takes over immediately and becomes sticky in turn.
 * The very first assignment (only, before any handoff has ever happened) is decided by: the
 * higher user score if scores differ, or whichever path shows the better latency during the
 * first ~5 seconds of monitoring if scores are equal. See preferredIdentityPath for the full
 * state machine.
 */
object DynamicWeightCalculator {

    private const val WINDOW_SIZE = 5
    private const val MIN_SAMPLES_FOR_DECISION = WINDOW_SIZE
    private const val ADVANTAGE_RATIO = 1.10
    private const val MAX_WEIGHT = 85
    private const val MIN_WEIGHT = 15
    private const val RECOVERY_START_WEIGHT = 10
    private const val RECOVERY_STEP = 10

    private enum class Path { WIFI, CELLULAR }

    private data class Sample(
        val wifiLatencyMs: Long,
        val cellularLatencyMs: Long
    )

    private val samples = ArrayDeque<Sample>(WINDOW_SIZE)

    @Volatile private var wifiUserScore = 1
    @Volatile private var cellularUserScore = 1
    @Volatile private var currentWeights = NetworkWeights(50, 50)

    // Recovery/share state. This is deliberately separate from identity state.
    @Volatile private var recoveringPath: Path? = null
    @Volatile private var recoveryWeight = RECOVERY_START_WEIGHT

    // Identity/IP stability state. Never use this value to alter routing share. null means "no
    // owner assigned yet" - either nothing has ever been decided, or both paths are currently
    // down. See preferredIdentityPath for how this is assigned/handed off.
    @Volatile private var identityOwner: Path? = null

    @Synchronized
    fun configureUserScores(wifiScore: Int, cellularScore: Int) {
        wifiUserScore = wifiScore.coerceIn(1, 5)
        cellularUserScore = cellularScore.coerceIn(1, 5)

        // A score change is an explicit user command. It immediately controls routing share.
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        samples.clear()
        currentWeights = preferredWeights()
        AppLogger.log(
            "Weights",
            "configureUserScores wifiScore=$wifiUserScore cellularScore=$cellularUserScore -> weights=$currentWeights",
        )

        // A score change is an explicit user command: identity is freshly (re-)decided under
        // the 0-5s monitoring gate below. Once assigned, it stays sticky exactly as if scores
        // had never changed - see preferredIdentityPath.
        identityOwner = null
    }

    fun wifiScore(): Int = wifiUserScore
    fun cellularScore(): Int = cellularUserScore

    /** The path currently ranked higher by the user. Equal scores intentionally return null. */
    fun preferredPath(): String? = when {
        wifiUserScore > cellularUserScore -> "wifi"
        cellularUserScore > wifiUserScore -> "cellular"
        else -> null
    }

    /**
     * Selects which physical path may determine the externally visible IP/identity.
     *
     * State machine (see the class doc comment for the summary):
     *  - Exactly one path up -> that path owns identity immediately, no monitoring wait
     *    (there is no choice to make, and an active outage must fail over instantly, not wait
     *    on a monitoring window meant only for comparing two healthy paths). This ALSO updates
     *    the sticky owner, so when the other path comes back the previous owner is not silently
     *    reinstated - identity stays on whichever path just carried the traffic during the
     *    outage until that one itself goes down.
     *  - Both paths up, an owner is already assigned -> sticky: keep it regardless of
     *    score/latency. The owner only ever changes via the single-path branch above, i.e.
     *    only by actually going down - never by the other path merely "looking better".
     *  - Both paths up, no owner yet (first-ever decision, or both were down and both just came
     *    back simultaneously) -> gated by ~5 seconds of real monitoring (samples.size reaching
     *    MIN_SAMPLES_FOR_DECISION, matching the probe loop's ~1s cadence) so the very first
     *    pick is not made on zero information. Unequal scores: the higher score wins outright.
     *    Equal scores: whichever path had the better (lower) median latency during that
     *    monitoring window wins.
     *
     * Routing weights are never changed by this method.
     */
    @Synchronized
    fun preferredIdentityPath(wifiAvailable: Boolean, cellularAvailable: Boolean): String? {
        if (!wifiAvailable && !cellularAvailable) {
            // Nothing to own. Deliberately do NOT keep the old owner "pending" - once both
            // paths have been down, whichever comes back first should not be forced to wait on
            // the other, so the single-path branches below are free to (re-)assign fresh.
            identityOwner = null
            return null
        }

        if (wifiAvailable && !cellularAvailable) {
            identityOwner = Path.WIFI
            return "wifi"
        }
        if (!wifiAvailable && cellularAvailable) {
            identityOwner = Path.CELLULAR
            return "cellular"
        }

        // Both paths are up from here on.
        identityOwner?.let { return it.name.lowercase() }

        // No owner yet: decide the very first assignment, gated by ~5s of real monitoring.
        if (samples.size < MIN_SAMPLES_FOR_DECISION) return null

        identityOwner = if (wifiUserScore != cellularUserScore) {
            if (wifiUserScore > cellularUserScore) Path.WIFI else Path.CELLULAR
        } else {
            val wifiMedian = median(samples.map { it.wifiLatencyMs })
            val cellularMedian = median(samples.map { it.cellularLatencyMs })
            if (wifiMedian <= cellularMedian) Path.WIFI else Path.CELLULAR
        }
        return identityOwner!!.name.lowercase()
    }

    /** Returns whether [path] is currently eligible to determine identity/IP. */
    @Synchronized
    fun isIdentityEligible(path: String): Boolean {
        val normalized = path.lowercase()
        if (wifiUserScore != cellularUserScore) {
            return preferredPath() == normalized
        }
        val selected = preferredIdentityPath(true, true)
        return selected == normalized
    }

    @Synchronized
    fun update(
        wifiAvailable: Boolean,
        wifiLatency: Long,
        cellularAvailable: Boolean,
        cellularLatency: Long
    ): NetworkWeights {
        val result = updateInternal(wifiAvailable, wifiLatency, cellularAvailable, cellularLatency)
        AppLogger.log(
            "Weights",
            "update(wifiAvail=$wifiAvailable wifiLatency=$wifiLatency cellAvail=$cellularAvailable cellLatency=$cellularLatency userScores=$wifiUserScore/$cellularUserScore) -> $result",
        )
        return result
    }

    private fun updateInternal(
        wifiAvailable: Boolean,
        wifiLatency: Long,
        cellularAvailable: Boolean,
        cellularLatency: Long
    ): NetworkWeights {
        if (!wifiAvailable && !cellularAvailable) {
            clear()
            return NetworkWeights(0, 0)
        }

        if (wifiAvailable && !cellularAvailable) {
            samples.clear()
            recoveringPath = null
            currentWeights = NetworkWeights(100, 0)
            return currentWeights
        }

        if (!wifiAvailable && cellularAvailable) {
            samples.clear()
            recoveringPath = null
            currentWeights = NetworkWeights(0, 100)
            return currentWeights
        }

        val wifi = wifiLatency.coerceAtLeast(1)
        val cellular = cellularLatency.coerceAtLeast(1)

        if (samples.size == WINDOW_SIZE) samples.removeFirst()
        samples.addLast(Sample(wifi, cellular))

        // USER SCORES ARE AUTHORITATIVE. Quality is not allowed to override an unequal score.
        if (wifiUserScore != cellularUserScore) {
            recoveringPath = null
            currentWeights = preferredWeights()
            return currentWeights
        }

        val preferred = preferredWeights()

        if (samples.size < MIN_SAMPLES_FOR_DECISION) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiLatencies = samples.map { it.wifiLatencyMs }
        val cellularLatencies = samples.map { it.cellularLatencyMs }
        val wifiMedian = median(wifiLatencies)
        val cellularMedian = median(cellularLatencies)
        val wifiStability = medianAbsoluteDeviation(wifiLatencies, wifiMedian)
        val cellularStability = medianAbsoluteDeviation(cellularLatencies, cellularMedian)
        val wifiCost = (wifiMedian + wifiStability * 2L).coerceAtLeast(1L)
        val cellularCost = (cellularMedian + cellularStability * 2L).coerceAtLeast(1L)

        val wifiBetter = wifiCost.toDouble() * ADVANTAGE_RATIO < cellularCost.toDouble()
        val cellularBetter = cellularCost.toDouble() * ADVANTAGE_RATIO < wifiCost.toDouble()

        if (!wifiBetter && !cellularBetter) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiQualityScore = 1.0 / wifiCost.toDouble()
        val cellularQualityScore = 1.0 / cellularCost.toDouble()
        val total = wifiQualityScore + cellularQualityScore
        val rawWifi = ((wifiQualityScore / total) * 100.0).toInt()

        val qualityWeights = if (wifiBetter) {
            val clamped = rawWifi.coerceIn(51, MAX_WEIGHT)
            NetworkWeights(clamped, 100 - clamped)
        } else {
            val clamped = rawWifi.coerceIn(MIN_WEIGHT, 49)
            NetworkWeights(clamped, 100 - clamped)
        }

        // Recovery share is only a share rule. It remains independent from identity/IP.
        val recovery = recoveringPath
        if (recovery != null) {
            val recoveredIsBetter = (recovery == Path.WIFI && wifiBetter) ||
                (recovery == Path.CELLULAR && cellularBetter)

            if (recoveredIsBetter) {
                recoveryWeight = (recoveryWeight + RECOVERY_STEP).coerceAtMost(
                    if (recovery == Path.WIFI) preferred.wifiWeight else preferred.cellularWeight
                )
                currentWeights = if (recovery == Path.WIFI) {
                    NetworkWeights(recoveryWeight, 100 - recoveryWeight)
                } else {
                    NetworkWeights(100 - recoveryWeight, recoveryWeight)
                }
                if (currentWeights == preferred) recoveringPath = null
            } else {
                currentWeights = if (recovery == Path.WIFI) {
                    NetworkWeights(recoveryWeight, 100 - recoveryWeight)
                } else {
                    NetworkWeights(100 - recoveryWeight, recoveryWeight)
                }
            }
            return currentWeights
        }

        currentWeights = qualityWeights
        return currentWeights
    }

    /** Reintroduces a recovered path at 10%. This is a routing-share concern only - see
     *  preferredIdentityPath for how identity/IP reacts to the same recovery independently. */
    @Synchronized
    fun resetForPathRecovery(recoveredWifi: Boolean): NetworkWeights {
        samples.clear()
        recoveringPath = if (recoveredWifi) Path.WIFI else Path.CELLULAR
        recoveryWeight = RECOVERY_START_WEIGHT

        currentWeights = if (recoveredWifi) {
            NetworkWeights(RECOVERY_START_WEIGHT, 100 - RECOVERY_START_WEIGHT)
        } else {
            NetworkWeights(100 - RECOVERY_START_WEIGHT, RECOVERY_START_WEIGHT)
        }
        AppLogger.log(
            "Weights",
            "resetForPathRecovery(recoveredWifi=$recoveredWifi) -> weights=$currentWeights (userScores wifi=$wifiUserScore cellular=$cellularUserScore - this reset IGNORES them, next update() call should immediately re-assert them if they differ)",
        )
        return currentWeights
    }

    /** Backward-compatible recovery entry point for callers that do not know the recovered path. */
    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        samples.clear()
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = preferredWeights()
        return currentWeights
    }

    @Synchronized
    fun clear() {
        samples.clear()
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = preferredWeights()
        identityOwner = null
    }

    fun currentWeights(): NetworkWeights = currentWeights

    private fun preferredWeights(): NetworkWeights {
        val delta = ((cellularUserScore - wifiUserScore) * 10).coerceIn(-40, 40)
        val cellular = (50 + delta).coerceIn(10, 90)
        return NetworkWeights(100 - cellular, cellular)
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 1L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianAbsoluteDeviation(values: List<Long>, median: Long): Long {
        if (values.isEmpty()) return 0L
        return median(values.map { abs(it - median) })
    }
}

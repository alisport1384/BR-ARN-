package com.bigrocket.service

import android.net.Network

/**
 * Composite health state for one physical path (Wi-Fi or Cellular).
 *
 * ACTIVE       - the last probe on this path succeeded (or Android just reports it up with no
 *                contrary evidence yet). Full participant in the traffic split.
 * DEGRADED     - the current probe failed, but the failure is not (yet) trusted as a real path
 *                loss: either the path is busy carrying real traffic right now (see
 *                PathActivityMonitor), or too few consecutive probes have failed to declare it
 *                lost. Sessions on a DEGRADED path are left alone and it keeps its share of
 *                the traffic split - RTT/latency never demotes a path further than this by
 *                itself.
 * DISCONNECTED - Android reports the Network gone, or enough consecutive probe failures
 *                occurred with no corroborating real traffic. Only this state triggers session
 *                eviction/migration and drops the path's weight share to zero.
 */
enum class PathHealthState { ACTIVE, DEGRADED, DISCONNECTED }

/**
 * Tracks one physical path's health across probe cycles from several independent signals
 * instead of collapsing straight to connected/disconnected off a single latency sample:
 *
 *  1. Network availability - `network == null` (Android itself reports the path gone) always
 *     wins immediately, regardless of any other signal.
 *  2. Real-connectivity probe outcome - [LatencyTester] already performs a real TCP connect,
 *     not a ping, so a success here is direct proof the path can reach the internet.
 *  3. Recent real-traffic activity - if the path has carried genuine payload bytes very
 *     recently (see [PathActivityMonitor]), a probe failure on it is treated as the probe
 *     having queued behind that traffic, not as evidence the path died.
 *  4. Consecutive-failure / consecutive-success hysteresis with recovery confirmation - a
 *     single failed (or recovered) probe never flips the state on its own.
 *
 * RTT itself is intentionally never a disconnect signal here - callers use [lastGoodLatencyMs]
 * purely as a quality input (e.g. for traffic-share weighting), never to decide DISCONNECTED.
 */
class PathHealthTracker(
    private val failuresToDisconnect: Int,
    private val successesToRecover: Int,
    private val activityGraceMs: Long,
    private val lossWindowSize: Int = 5
) {
    var state: PathHealthState = PathHealthState.ACTIVE
        private set
    var lastGoodLatencyMs: Long = DEFAULT_LATENCY_MS
        private set

    private var consecutiveFailures = 0
    private var consecutiveSuccesses = 0
    private val recentOutcomes = ArrayDeque<Boolean>() // true = probe succeeded, oldest first

    /** Fraction of the last [lossWindowSize] probes that failed; 0.0 with no samples yet. */
    val recentLossRate: Double
        get() = if (recentOutcomes.isEmpty()) {
            0.0
        } else {
            recentOutcomes.count { !it }.toDouble() / recentOutcomes.size
        }

    /**
     * Feeds one probe cycle's result into the state machine and returns the resulting state.
     *
     * @param network the current Network reference for this path, or null if Android reports
     *   it gone entirely.
     * @param probeLatencyMs this cycle's [LatencyTester] result, or [LatencyTester.FAILURE].
     */
    fun update(network: Network?, probeLatencyMs: Long): PathHealthState {
        if (network == null) {
            state = PathHealthState.DISCONNECTED
            consecutiveFailures = 0
            consecutiveSuccesses = 0
            recentOutcomes.clear()
            return state
        }

        val probeSucceeded = probeLatencyMs != LatencyTester.FAILURE
        recordOutcome(probeSucceeded)

        if (probeSucceeded) {
            consecutiveFailures = 0
            consecutiveSuccesses = (consecutiveSuccesses + 1).coerceAtMost(successesToRecover)
            lastGoodLatencyMs = probeLatencyMs.coerceAtLeast(1)
            state = if (state == PathHealthState.DISCONNECTED && consecutiveSuccesses < successesToRecover) {
                // Still waiting for enough consecutive successes to trust the recovery.
                PathHealthState.DISCONNECTED
            } else {
                PathHealthState.ACTIVE
            }
            return state
        }

        // Probe failed. A path that is demonstrably moving real traffic right now gets the
        // benefit of the doubt instead of accumulating a failure strike - this is what
        // prevents a saturated-but-alive path from being evicted mid-transfer.
        if (PathActivityMonitor.hasRecentActivity(network, activityGraceMs)) {
            if (state != PathHealthState.DISCONNECTED) state = PathHealthState.DEGRADED
            return state
        }

        consecutiveSuccesses = 0
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(failuresToDisconnect)
        state = if (consecutiveFailures >= failuresToDisconnect) {
            PathHealthState.DISCONNECTED
        } else {
            PathHealthState.DEGRADED
        }
        return state
    }

    private fun recordOutcome(success: Boolean) {
        recentOutcomes.addLast(success)
        while (recentOutcomes.size > lossWindowSize) recentOutcomes.removeFirst()
    }

    /** Full reset for a fresh VPN session - must not carry state across stop/start cycles. */
    fun reset() {
        state = PathHealthState.ACTIVE
        lastGoodLatencyMs = DEFAULT_LATENCY_MS
        consecutiveFailures = 0
        consecutiveSuccesses = 0
        recentOutcomes.clear()
    }

    private companion object {
        const val DEFAULT_LATENCY_MS = 80L
    }
}

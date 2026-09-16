package com.bigrocket.bonding.core

import com.bigrocket.bonding.api.BondedChannel
import com.bigrocket.bonding.api.BondingStatus
import com.bigrocket.bonding.api.PathStatus
import com.bigrocket.bonding.buffer.WindowedReorderBuffer
import com.bigrocket.bonding.frame.Frame
import com.bigrocket.bonding.frame.FrameFlags
import com.bigrocket.bonding.frame.FrameProcessor
import com.bigrocket.bonding.recovery.RecoveryManager
import com.bigrocket.bonding.recovery.RetransmitCache
import com.bigrocket.bonding.scheduler.FrameScheduler
import com.bigrocket.bonding.transport.PathRuntimeImpl
import com.bigrocket.bonding.transport.PathState
import com.bigrocket.bonding.transport.UdpPathAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Section 18/330 (locked public contract). Kept alongside [BondedChannel] rather than replacing
 *  it - see [BondingEngineImpl]'s class doc for why one class implements both. */
interface BondingEngine {
    fun start()
    fun stop()
    fun send(data: ByteArray)
    fun state(): BondingState
}

/**
 * Section 205 (Core Entry Point) / Section 330. One instance is a complete, standalone bonded
 * endpoint: it is simultaneously the "Splitter" for its own outbound data (fragment -> schedule
 * -> per-path queue -> per-path sender, Section 50) and the "Joiner" for whatever arrives back on
 * the same paths (per-path receiver -> validate -> reorder buffer -> ordered output, Section 51)
 * - see the specification document's very first diagram, which is exactly this bidirectional
 * shape. Implements both [BondedChannel] (Section 206, the intended public API - `open`/`close`)
 * and [BondingEngine] (Section 330's locked contract - `start`/`stop`) because both are the
 * "final" contract in different sections of the same specification for what is unmistakably the
 * same object; `open()`/`close()` simply delegate to `start()`/`stop()`.
 *
 * Section 45/46: this class owns lifecycle and wiring only. It does not decide which path a
 * frame takes (that's [scheduler]), does not know how a frame reaches the network (that's each
 * [PathRuntimeImpl]'s [com.bigrocket.bonding.transport.PathAdapter]), and does not decide
 * delivery order (that's [WindowedReorderBuffer]).
 */
class BondingEngineImpl(
    streamId: Int,
    private val pathRuntimes: List<PathRuntimeImpl>,
    private val scheduler: FrameScheduler,
    private val config: BondingConfig,
) : BondedChannel, BondingEngine {

    private val frameProcessor = FrameProcessor(streamId, config.maxPayloadSize)
    private val retransmitCache = RetransmitCache()

    /** Section 216/328: not wired by default - a standalone engine with no known peer has no one
     *  to ask for a resend. [com.bigrocket.bonding.sandbox.BondingSandbox] wires this once both
     *  ends of a test pair exist (see [RecoveryManager]'s class doc for why that wiring is
     *  same-process for now). Left null, gaps just ride out [reorderBuffer]'s own hard timeout
     *  (Section 19 Mode 1). */
    var recoveryManager: RecoveryManager? = null

    private val framesDelivered = AtomicLong(0)
    private val framesSent = AtomicLong(0)
    private val framesDroppedNoPath = AtomicLong(0)

    private val reorderBuffer = WindowedReorderBuffer(
        windowSize = config.reorderWindowSize,
        softTimeoutMs = config.reorderSoftTimeoutMs,
        hardTimeoutMs = config.reorderHardTimeoutMs,
        onMissingSequence = { missingSequence ->
            if (config.recoveryEnabled) recoveryManager?.request(missingSequence)
        },
    )

    private val outputQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile private var engineState: BondingState = BondingState.STOPPED
    private var scope: CoroutineScope? = null

    override fun open() = start()
    override fun close() = stop()
    override fun state(): BondingState = engineState

    override fun start() {
        check(engineState == BondingState.STOPPED) { "BondingEngine already started (state=$engineState)" }
        transitionTo(BondingState.STARTING)

        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = engineScope

        pathRuntimes.forEach { path ->
            runCatching { path.start() }
                .onFailure { path.transitionTo(PathState.FAILED) }
        }

        pathRuntimes.forEach { path -> launchSenderLoop(engineScope, path) }
        pathRuntimes.forEach { path -> launchReceiverLoop(engineScope, path) }
        launchMetricsLoop(engineScope)
        launchReassemblyLoop(engineScope)
        launchPathWatchdogLoop(engineScope)

        transitionTo(BondingState.ACTIVE)
    }

    override fun stop() {
        if (engineState == BondingState.STOPPED) return
        transitionTo(BondingState.STOPPING)
        scope?.cancel()
        scope = null
        pathRuntimes.forEach { runCatching { it.stop() } }
        transitionTo(BondingState.STOPPED)
    }

    override fun send(data: ByteArray) {
        check(engineState == BondingState.ACTIVE || engineState == BondingState.DEGRADED) {
            "Cannot send while engine state is $engineState"
        }
        val frames = frameProcessor.createFrames(data)
        for (frame in frames) {
            retransmitCache.record(frame)
            framesSent.incrementAndGet()
            dispatch(frame)
        }
    }

    override fun receive(): ByteArray? = outputQueue.poll()

    override fun status(): BondingStatus = BondingStatus(
        engineState = engineState,
        paths = pathRuntimes.map { path ->
            PathStatus(
                id = path.id,
                state = path.state,
                weightPercent = scheduler.currentWeightPercent(path.id),
                metrics = path.metrics,
                queueDepth = path.sendQueue.size(),
                queueDropped = (path.sendQueue as? com.bigrocket.bonding.buffer.BoundedSendQueue)?.droppedCount ?: 0,
            )
        },
        framesSent = framesSent.get(),
        framesDelivered = framesDelivered.get(),
        framesDroppedTooOld = reorderBuffer.droppedTooOldCount,
        framesDroppedTooFarAhead = reorderBuffer.droppedTooFarAheadCount,
        framesPermanentlyLost = reorderBuffer.permanentlyLostCount,
        framesDroppedNoPath = framesDroppedNoPath.get(),
        recoveryRequested = (recoveryManager as? com.bigrocket.bonding.recovery.SelectiveRecoveryManager)?.stats?.requested ?: 0,
        recoveryFulfilled = (recoveryManager as? com.bigrocket.bonding.recovery.SelectiveRecoveryManager)?.stats?.fulfilled ?: 0,
    )

    /** Injection point used only by [com.bigrocket.bonding.sandbox.BondingSandbox]'s same-process
     *  recovery wiring (Section 145: a resent RECOVERY_DATA frame is delivered exactly like any
     *  other arriving frame). Not part of [BondedChannel]/[BondingEngine] - external callers have
     *  no legitimate reason to inject a frame directly. */
    internal fun injectRecoveredFrame(frame: Frame) {
        reorderBuffer.push(frame)
    }

    /** See [com.bigrocket.bonding.core.createRecoveryManagerFor] - internal, not part of any
     *  public contract; exists purely so that extension function doesn't need [retransmitCache]
     *  itself to be anything other than `private`. */
    internal fun retransmitCacheForRecovery() = retransmitCache

    private fun dispatch(frame: Frame) {
        val path = scheduler.selectPath(pathRuntimes) as? PathRuntimeImpl
        if (path == null) {
            // Section 59: No Available Path -> Reject. Nothing to retry onto - if this frame's
            // sequence is ever needed, the far side's own gap detection will request it once a
            // path does come back, same as an ordinary loss.
            framesDroppedNoPath.incrementAndGet()
            return
        }
        path.enqueue(frame) // drop-on-overflow policy already lives in BoundedSendQueue
    }

    private fun launchSenderLoop(scope: CoroutineScope, path: PathRuntimeImpl) = scope.launch {
        while (isActive) {
            if (path.state == PathState.FAILED || path.state == PathState.CLOSED) {
                delay(SENDER_IDLE_POLL_MS)
                continue
            }
            val frame = path.sendQueue.poll()
            if (frame == null) {
                delay(SENDER_IDLE_POLL_MS)
                continue
            }
            try {
                path.adapter.send(frame)
            } catch (_: Exception) {
                path.transitionTo(PathState.FAILED) // Section 410: Transport Failure -> Path State Update
            }
        }
    }

    private fun launchReceiverLoop(scope: CoroutineScope, path: PathRuntimeImpl) = scope.launch {
        while (isActive) {
            if (path.state == PathState.FAILED || path.state == PathState.CLOSED) {
                delay(SENDER_IDLE_POLL_MS)
                continue
            }
            val frame = try {
                path.adapter.receive()
            } catch (_: Exception) {
                null
            }
            if (frame == null) {
                // Normally paced by the adapter's own blocking socket-read timeout; this tiny
                // delay is only a safety net against a busy-spin if that assumption ever breaks
                // (e.g. a future adapter whose receive() returns immediately).
                delay(RECEIVER_IDLE_POLL_MS)
                continue
            }

            if (FrameFlags.has(frame.header.flags, FrameFlags.CONTROL)) {
                continue // HEARTBEAT/HEARTBEAT_ACK are handled inside the adapter itself
            }
            reorderBuffer.push(frame)
        }
    }

    private fun launchReassemblyLoop(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val ordered = reorderBuffer.readOrdered()
            for (frame in ordered) {
                outputQueue.add(frameProcessor.extractPayload(frame))
                framesDelivered.incrementAndGet()
            }
            delay(REASSEMBLY_POLL_MS)
        }
    }

    private fun launchMetricsLoop(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            for (path in pathRuntimes) {
                if (path.state == PathState.CLOSED) continue
                (path.adapter as? UdpPathAdapter)?.sendHeartbeat()
                val metrics = path.adapter.getMetrics()
                path.updateMetrics(metrics)
                scheduler.update(path.id, metrics)
            }
            updateEngineDegradedState()
            delay(config.metricsUpdateIntervalMs)
        }
    }

    /** Section 292/523: engine-level DEGRADED means zero currently-usable paths (bonding has
     *  nothing to bond onto right now); with at least one usable path the engine is ACTIVE even
     *  if the other is down (Section 268 "Remaining Path Weight = 100%%, Continue Stream" - a
     *  single failed path is the *scheduler's* problem to route around, not the engine's problem
     *  to surface as degraded). */
    private fun updateEngineDegradedState() {
        val eligibleStates = setOf(PathState.ACTIVE, PathState.DEGRADED, PathState.RECOVERING)
        val anyUsable = pathRuntimes.any { it.state in eligibleStates }
        when {
            !anyUsable && engineState == BondingState.ACTIVE -> transitionTo(BondingState.DEGRADED)
            anyUsable && engineState == BondingState.DEGRADED -> transitionTo(BondingState.ACTIVE)
        }
    }

    /** Section 66/282 (Path Recovery Sequence): periodically retries connecting a FAILED path;
     *  on success it re-enters through RECOVERING (never straight back to ACTIVE, Section 461),
     *  and [com.bigrocket.bonding.scheduler.AdaptiveScheduler] picks up the RECOVERING -> ACTIVE
     *  edge itself to apply the Section 283 weight ramp. */
    private fun launchPathWatchdogLoop(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(PATH_RECOVERY_CHECK_INTERVAL_MS)
            for (path in pathRuntimes) {
                if (path.state != PathState.FAILED) continue
                val reconnected = runCatching { path.adapter.connect() }.isSuccess
                if (reconnected) {
                    path.transitionTo(PathState.RECOVERING)
                    path.transitionTo(PathState.ACTIVE)
                }
            }
        }
    }

    private fun transitionTo(next: BondingState) {
        if (engineState.canTransitionTo(next)) engineState = next
    }

    companion object {
        private const val SENDER_IDLE_POLL_MS = 5L
        private const val RECEIVER_IDLE_POLL_MS = 5L
        private const val REASSEMBLY_POLL_MS = 5L
        private const val PATH_RECOVERY_CHECK_INTERVAL_MS = 3_000L
    }
}

package com.xiangqi.assist.assist

/**
 * Independent health policy for capture → stable-frame accumulation → recognition → landing verify.
 * Timestamps describe real pipeline work, not UI phase transitions.
 */
object PipelineHealthPolicy {
    /** VERIFYING grace after the last genuinely processed/recognized image. */
    const val LANDING_FRAME_GRACE_MS = 1_750L
    /** Hard cap for a gesture/verification transaction. */
    const val LANDING_ABSOLUTE_MS = 4_500L
    /** A single recognition job may legitimately run this long before the pipeline is rebuilt. */
    const val INFERENCE_STUCK_MS = 6_000L
    /** No ImageReader callbacks: rebuild the capture side. */
    const val STREAM_FRAME_STALL_MS = 1_250L
    /** Frames arrive but the stable window makes no progress. */
    const val STREAM_STABLE_STALL_MS = 3_000L
    /** No recognition completion (valid or rejected) after stable frames were produced. */
    const val VISION_STALL_MS = 5_000L
    /** Rate limit for destructive pipeline rebuild/reset actions. */
    const val MIN_RECOVERY_INTERVAL_MS = 1_250L
    /** Global quick probe: poke acquisition after 500ms without a normally processed sample. */
    const val FRAME_PROGRESS_KICK_MS = 500L
    /** Do not queue repeated forced acquisitions faster than the two-frame probe period. */
    const val CAPTURE_KICK_COOLDOWN_MS = 500L

    enum class Action {
        NONE,
        /** A single non-destructive acquireLatestImage probe; the stable-window rule still applies. */
        KICK_CAPTURE,
        REBASE_LANDING,
        RESET_VISION,
        REBUILD_CAPTURE,
    }

    data class Health(
        val paused: Boolean = true,
        val manualMode: Boolean = false,
        val needsFrames: Boolean = false,
        val landingStage: LandingFlow.Stage? = null,
        val landingStartedAt: Long = 0L,
        val landingCompletedAt: Long = 0L,
        val lastFrameAt: Long = 0L,
        val streamStartedAt: Long = 0L,
        val captureStartedAt: Long = 0L,
        val lastStableAt: Long = 0L,
        val lastVisionAt: Long = 0L,
        val visionEpochStartedAt: Long = 0L,
        /** Updated for every completed recognition job, including a safely rejected board. */
        val lastRecognitionCompletedAt: Long = 0L,
        /** Last sample fully consumed by the capture handler. */
        val lastProcessedSampleAt: Long = 0L,
        val inferenceInFlight: Boolean = false,
        val inferenceStartedAt: Long = 0L,
        val recognitionPending: Boolean = false,
        val recognitionPendingSinceAt: Long = 0L,
        val captureAlive: Boolean = true,
        val lastRecoveryAt: Long = 0L,
        val lastCaptureKickAt: Long = 0L,
        val now: Long = 0L,
    )

    fun evaluate(h: Health): Action {
        if (h.now <= 0L || h.paused || h.manualMode) return Action.NONE

        // A dispatched gesture must never be interrupted by capture recovery. Its own clock
        // nevertheless closes a genuinely stuck verify transaction, even if capture died.
        if (h.landingStage != null && landingStalled(h)) return Action.REBASE_LANDING
        if (!h.needsFrames || !h.captureAlive) return Action.NONE

        val pendingAge = if (h.recognitionPending && h.recognitionPendingSinceAt > 0L)
            h.now - h.recognitionPendingSinceAt else 0L
        if (h.inferenceInFlight && h.inferenceStartedAt > 0L &&
            h.now - h.inferenceStartedAt > INFERENCE_STUCK_MS
        ) {
            if (recoveryAllowed(h)) return Action.REBUILD_CAPTURE
            return Action.NONE
        }
        if (pendingAge > INFERENCE_STUCK_MS) {
            if (recoveryAllowed(h)) return Action.RESET_VISION
            return Action.NONE
        }

        // A healthy in-flight or queued job is evidence of progress. In particular, a fresh
        // session has no successful board yet (lastVisionAt==0); that alone is never a fault.
        if (h.inferenceInFlight || h.recognitionPending) return Action.NONE

        if (h.lastFrameAt <= 0L && h.captureStartedAt > 0L &&
            h.now - h.captureStartedAt > STREAM_FRAME_STALL_MS
        ) {
            return if (recoveryAllowed(h)) Action.REBUILD_CAPTURE else Action.NONE
        }
        if (h.lastFrameAt > 0L && h.now - h.lastFrameAt > STREAM_FRAME_STALL_MS) {
            return if (recoveryAllowed(h)) Action.REBUILD_CAPTURE else Action.NONE
        }

        val streamBase = maxOf(h.streamStartedAt, h.captureStartedAt)
        if (h.lastFrameAt > 0L && h.lastStableAt <= 0L && streamBase > 0L &&
            h.now - streamBase > STREAM_STABLE_STALL_MS
        ) {
            return if (recoveryAllowed(h)) Action.RESET_VISION else Action.NONE
        }
        if (h.lastFrameAt > 0L && h.lastStableAt > 0L &&
            (h.lastProcessedSampleAt <= 0L ||
                h.now - h.lastProcessedSampleAt > STREAM_STABLE_STALL_MS)
        ) {
            return if (recoveryAllowed(h)) Action.RESET_VISION else Action.NONE
        }

        if (h.lastStableAt > 0L) {
            val visionProgressAt = maxOf(
                h.lastVisionAt,
                h.lastRecognitionCompletedAt,
                h.visionEpochStartedAt,
                h.lastRecoveryAt,
            )
            if (visionProgressAt > 0L && h.now - visionProgressAt > VISION_STALL_MS) {
                return if (recoveryAllowed(h)) Action.RESET_VISION else Action.NONE
            }
        }

        // Non-destructive global probe. It runs only after the existing hard-stage watchdogs,
        // so a dead Surface is rebuilt instead of being poked forever.
        val sampleBase = maxOf(h.lastProcessedSampleAt, h.streamStartedAt, h.captureStartedAt)
        val sampleAge = if (sampleBase > 0L) h.now - sampleBase else Long.MAX_VALUE
        if (sampleAge >= FRAME_PROGRESS_KICK_MS && captureKickAllowed(h)) {
            return Action.KICK_CAPTURE
        }
        return Action.NONE
    }

    private fun landingStalled(h: Health): Boolean {
        val base = maxOf(h.landingCompletedAt, h.landingStartedAt)
        if (base <= 0L) return false
        if (h.now - base > LANDING_ABSOLUTE_MS) return true
        if (h.landingStage != LandingFlow.Stage.VERIFYING) return false

        // A healthy capture stream may spend one complete stable window collecting samples,
        // then another interval running YOLO. Those are real in-flight progress even before a
        // new valid board is committed; do not abandon VERIFYING solely because visionAt is old.
        val recognitionStartedAt = when {
            h.inferenceInFlight -> h.inferenceStartedAt
            h.recognitionPending -> h.recognitionPendingSinceAt
            else -> 0L
        }
        if (recognitionStartedAt > 0L && h.now - recognitionStartedAt <= INFERENCE_STUCK_MS) {
            return false
        }
        val progress = maxOf(
            base,
            h.lastVisionAt,
            h.lastRecognitionCompletedAt,
            h.lastStableAt,
            h.lastProcessedSampleAt,
        )
        return h.now - progress > LANDING_FRAME_GRACE_MS
    }

    private fun recoveryAllowed(h: Health): Boolean =
        h.lastRecoveryAt <= 0L || h.now - h.lastRecoveryAt >= MIN_RECOVERY_INTERVAL_MS

    private fun captureKickAllowed(h: Health): Boolean =
        h.lastCaptureKickAt <= 0L || h.now - h.lastCaptureKickAt >= CAPTURE_KICK_COOLDOWN_MS
}

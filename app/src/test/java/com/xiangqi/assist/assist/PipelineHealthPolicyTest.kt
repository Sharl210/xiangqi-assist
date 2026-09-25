package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineHealthPolicyTest {
    private fun base(
        now: Long = 100_000L,
        paused: Boolean = false,
        manualMode: Boolean = false,
        needsFrames: Boolean = true,
        landingStage: LandingFlow.Stage? = null,
        landingStartedAt: Long = 0L,
        landingCompletedAt: Long = 0L,
        lastFrameAt: Long = now,
        streamStartedAt: Long = now,
        captureStartedAt: Long = now,
        lastStableAt: Long = now,
        lastVisionAt: Long = now,
        visionEpochStartedAt: Long = now,
        lastRecognitionCompletedAt: Long = now,
        lastProcessedSampleAt: Long = now,
        inferenceInFlight: Boolean = false,
        inferenceStartedAt: Long = 0L,
        recognitionPending: Boolean = false,
        recognitionPendingSinceAt: Long = 0L,
        captureAlive: Boolean = true,
        lastRecoveryAt: Long = 0L,
        lastCaptureKickAt: Long = Long.MIN_VALUE,
    ) = PipelineHealthPolicy.Health(
        paused = paused,
        manualMode = manualMode,
        needsFrames = needsFrames,
        landingStage = landingStage,
        landingStartedAt = landingStartedAt,
        landingCompletedAt = landingCompletedAt,
        lastFrameAt = lastFrameAt,
        streamStartedAt = streamStartedAt,
        captureStartedAt = captureStartedAt,
        lastStableAt = lastStableAt,
        lastVisionAt = lastVisionAt,
        visionEpochStartedAt = visionEpochStartedAt,
        lastRecognitionCompletedAt = lastRecognitionCompletedAt,
        lastProcessedSampleAt = lastProcessedSampleAt,
        inferenceInFlight = inferenceInFlight,
        inferenceStartedAt = inferenceStartedAt,
        recognitionPending = recognitionPending,
        recognitionPendingSinceAt = recognitionPendingSinceAt,
        captureAlive = captureAlive,
        lastRecoveryAt = lastRecoveryAt,
        lastCaptureKickAt = lastCaptureKickAt,
        now = now,
    )

    @Test fun `all affected watchdog deadlines are half their previous values`() {
        assertEquals(1_750L, PipelineHealthPolicy.LANDING_FRAME_GRACE_MS)
        assertEquals(4_500L, PipelineHealthPolicy.LANDING_ABSOLUTE_MS)
        assertEquals(6_000L, PipelineHealthPolicy.INFERENCE_STUCK_MS)
        assertEquals(1_250L, PipelineHealthPolicy.STREAM_FRAME_STALL_MS)
        assertEquals(3_000L, PipelineHealthPolicy.STREAM_STABLE_STALL_MS)
        assertEquals(5_000L, PipelineHealthPolicy.VISION_STALL_MS)
        assertEquals(1_250L, PipelineHealthPolicy.MIN_RECOVERY_INTERVAL_MS)
        assertEquals(500L, PipelineHealthPolicy.FRAME_PROGRESS_KICK_MS)
    }

    @Test fun `healthy pipeline and engine-only phase need no action`() {
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(base()))
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(base(
            needsFrames = false,
            lastProcessedSampleAt = 1L,
            lastFrameAt = 1L,
        )))
    }

    @Test fun `paused or manual phase is never recovered`() {
        val stale = base(now = 100_000L, lastProcessedSampleAt = 1L, lastFrameAt = 1L, lastStableAt = 1L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(stale.copy(paused = true)))
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(stale.copy(manualMode = true)))
    }

    @Test fun `global kick is idle until 500ms then fires once per cooldown`() {
        val last = 10_000L
        val h = base(now = last + 499L, lastProcessedSampleAt = last, lastFrameAt = last,
            streamStartedAt = last, captureStartedAt = last,
            lastStableAt = last, lastVisionAt = last, lastRecognitionCompletedAt = last)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.KICK_CAPTURE,
            PipelineHealthPolicy.evaluate(h.copy(now = last + 500L)))
        assertEquals(PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(h.copy(now = last + 700L, lastCaptureKickAt = last + 500L)))
        assertEquals(PipelineHealthPolicy.Action.KICK_CAPTURE,
            PipelineHealthPolicy.evaluate(h.copy(now = last + 1_000L, lastCaptureKickAt = last + 500L)))
    }

    @Test fun `fresh processed samples keep the global kick from clearing a stable window`() {
        val now = 100_000L
        val h = base(now = now, lastProcessedSampleAt = now, lastStableAt = now - 400L,
            lastVisionAt = now - 400L, lastRecognitionCompletedAt = now - 400L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(h.copy(now = now + 499L, lastProcessedSampleAt = now + 499L)))
    }

    @Test fun `recent inference after reset suppresses stale vision recovery`() {
        val now = 100_000L
        val h = base(now = now, lastFrameAt = now - 4L, lastStableAt = now - 119L,
            lastVisionAt = 0L, visionEpochStartedAt = now - 536L,
            lastRecognitionCompletedAt = 0L, lastProcessedSampleAt = now - 4L,
            inferenceInFlight = true, inferenceStartedAt = now - 106L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test fun `in flight inference is allowed until half timeout and then rebuilt`() {
        val started = 100_000L
        val h = base(now = started + PipelineHealthPolicy.INFERENCE_STUCK_MS - 1L,
            lastProcessedSampleAt = started, inferenceInFlight = true, inferenceStartedAt = started)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.REBUILD_CAPTURE,
            PipelineHealthPolicy.evaluate(h.copy(now = started + PipelineHealthPolicy.INFERENCE_STUCK_MS + 1L)))
    }

    @Test fun `queued recognition blocks reset while it is being consumed`() {
        val now = 100_000L
        val h = base(now = now, lastProcessedSampleAt = now - 10_000L, lastVisionAt = 0L,
            lastRecognitionCompletedAt = 0L, recognitionPending = true,
            recognitionPendingSinceAt = now - 1_000L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION,
            PipelineHealthPolicy.evaluate(h.copy(recognitionPendingSinceAt = now - PipelineHealthPolicy.INFERENCE_STUCK_MS - 1L)))
    }

    @Test fun `completed rejected recognition counts as channel progress without inventing a valid board`() {
        val now = 100_000L
        val h = base(now = now, lastFrameAt = now - 100L, streamStartedAt = now - 100L,
            captureStartedAt = now - 100L, lastVisionAt = 0L, visionEpochStartedAt = now - 15_000L,
            lastRecognitionCompletedAt = now - 100L, lastStableAt = now - 100L,
            lastProcessedSampleAt = now - 100L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION,
            PipelineHealthPolicy.evaluate(h.copy(now = now + PipelineHealthPolicy.VISION_STALL_MS + 1L,
                lastFrameAt = now + PipelineHealthPolicy.VISION_STALL_MS + 1L,
                streamStartedAt = now + PipelineHealthPolicy.VISION_STALL_MS + 1L,
                captureStartedAt = now + PipelineHealthPolicy.VISION_STALL_MS + 1L,
                lastRecognitionCompletedAt = now - 10_000L, lastProcessedSampleAt = now - 10_000L,
                lastStableAt = now - 10_000L)))
    }

    @Test fun `no frames rebuilds only after half timeout`() {
        val start = 80_000L
        val h = base(now = start + PipelineHealthPolicy.STREAM_FRAME_STALL_MS - 1L,
            lastProcessedSampleAt = start - 10_000L, lastFrameAt = start,
            streamStartedAt = start, captureStartedAt = start,
            lastStableAt = start, lastVisionAt = start, lastRecognitionCompletedAt = start,
            lastCaptureKickAt = start + PipelineHealthPolicy.STREAM_FRAME_STALL_MS - 1L)
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION, PipelineHealthPolicy.evaluate(h))
        assertEquals(PipelineHealthPolicy.Action.REBUILD_CAPTURE,
            PipelineHealthPolicy.evaluate(h.copy(now = start + PipelineHealthPolicy.STREAM_FRAME_STALL_MS + 1L,
                lastRecoveryAt = 0L)))
    }

    @Test fun `first eight-frame stable window can fill without vision timestamp`() {
        val start = 100_000L
        val h = base(now = start + 875L, lastFrameAt = start + 875L,
            streamStartedAt = start, captureStartedAt = start,
            lastStableAt = 0L, lastVisionAt = 0L, visionEpochStartedAt = start,
            lastRecognitionCompletedAt = 0L, lastProcessedSampleAt = start + 875L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test fun `a released stable window stays healthy while samples continue to be processed`() {
        val now = 100_000L
        val h = base(
            now = now,
            lastStableAt = now - 10_000L,
            lastVisionAt = now - 9_000L,
            lastRecognitionCompletedAt = now - 9_000L,
            lastProcessedSampleAt = now,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }
    @Test fun `stable and vision stalls recover after their halved deadlines`() {
        val start = 100_000L
        val stableNow = start + PipelineHealthPolicy.STREAM_STABLE_STALL_MS - 1L
        val healthy = base(now = stableNow,
            lastProcessedSampleAt = stableNow, lastFrameAt = stableNow,
            streamStartedAt = start, captureStartedAt = start,
            lastStableAt = 0L, lastVisionAt = 0L, visionEpochStartedAt = start,
            lastRecognitionCompletedAt = 0L, lastCaptureKickAt = stableNow)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(healthy))
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION,
            PipelineHealthPolicy.evaluate(healthy.copy(now = start + PipelineHealthPolicy.STREAM_STABLE_STALL_MS + 1L)))

        val visionNow = start + PipelineHealthPolicy.VISION_STALL_MS - 1L
        val vision = base(now = visionNow,
            lastProcessedSampleAt = visionNow, lastFrameAt = visionNow,
            streamStartedAt = visionNow, captureStartedAt = visionNow,
            lastStableAt = visionNow, lastVisionAt = start,
            visionEpochStartedAt = start - 20_000L, lastRecognitionCompletedAt = start,
            lastCaptureKickAt = visionNow)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(vision))
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION,
            PipelineHealthPolicy.evaluate(vision.copy(now = start + PipelineHealthPolicy.VISION_STALL_MS + 1L,
                lastRecoveryAt = 0L)))
    }

    @Test fun `landing grace and absolute limit are halved`() {
        val completed = 100_000L
        val noProgress = base(now = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS - 1L,
            landingStage = LandingFlow.Stage.VERIFYING, landingStartedAt = completed - 200L,
            landingCompletedAt = completed, lastVisionAt = completed - 100L,
            lastRecognitionCompletedAt = completed - 100L, lastStableAt = completed - 100L,
            lastProcessedSampleAt = completed - 100L,
            lastCaptureKickAt = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS - 1L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(noProgress))
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING,
            PipelineHealthPolicy.evaluate(noProgress.copy(now = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS + 1L)))

        val dispatching = base(now = completed + PipelineHealthPolicy.LANDING_ABSOLUTE_MS + 1L,
            landingStage = LandingFlow.Stage.DISPATCHING, landingStartedAt = completed,
            landingCompletedAt = 0L, lastVisionAt = completed, lastRecognitionCompletedAt = completed)
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING, PipelineHealthPolicy.evaluate(dispatching))
    }

    @Test fun `processed capture samples count as landing progress before recognition commits a board`() {
        val completed = 100_000L
        val now = completed + 2_000L
        val flowing = base(
            now = now,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 200L,
            landingCompletedAt = completed,
            lastVisionAt = completed,
            lastRecognitionCompletedAt = completed,
            lastStableAt = now - 125L,
            lastProcessedSampleAt = now - 125L,
            lastCaptureKickAt = now,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(flowing))
    }

    @Test fun `healthy in flight recognition can finish but landing absolute cap still applies`() {
        val completed = 100_000L
        val inFlight = base(
            now = completed + 3_000L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 200L,
            landingCompletedAt = completed,
            lastVisionAt = completed,
            lastRecognitionCompletedAt = completed,
            lastProcessedSampleAt = completed + 125L,
            inferenceInFlight = true,
            inferenceStartedAt = completed + 1_200L,
            lastCaptureKickAt = completed + 3_000L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(inFlight))
        assertEquals(
            PipelineHealthPolicy.Action.REBASE_LANDING,
            PipelineHealthPolicy.evaluate(inFlight.copy(now = completed + PipelineHealthPolicy.LANDING_ABSOLUTE_MS + 1L)),
        )
    }

    @Test fun `paused capture death and invalid clock never trigger recovery`() {
        assertEquals(PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(base(now = 0L)))
        assertEquals(PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(base(paused = true, lastProcessedSampleAt = 1L, lastFrameAt = 1L)))
        assertEquals(PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(base(captureAlive = false, lastProcessedSampleAt = 1L, lastFrameAt = 1L)))
    }
}

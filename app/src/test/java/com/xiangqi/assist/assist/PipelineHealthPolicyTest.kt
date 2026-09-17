package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 管线监督的判定表测试。
 *
 * 这些用例对应真机现场的两个现象：
 * 1. 手势已经落完，界面停在“核对落子中”，画面再也不回来，只能手动暂停再开始；
 * 2. 引擎搜索正常（本来就不取帧）时，监督器不许插手。
 */
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
        streamStartedAt: Long = if (lastFrameAt > 0L) lastFrameAt else 0L,
        captureStartedAt: Long = 0L,
        lastStableAt: Long = now,
        lastVisionAt: Long = now,
        inferenceInFlight: Boolean = false,
        inferenceStartedAt: Long = 0L,
        captureAlive: Boolean = true,
        lastRecoveryAt: Long = 0L,
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
        inferenceInFlight = inferenceInFlight,
        inferenceStartedAt = inferenceStartedAt,
        captureAlive = captureAlive,
        lastRecoveryAt = lastRecoveryAt,
        now = now,
    )

    // ==================== 正常情况绝不插手 ====================

    @Test
    fun `healthy pipeline needs no action`() {
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(base()))
    }

    @Test
    fun `engine only phases never trigger vision recovery`() {
        // 计算中/落子中按设计不取帧：画面时间戳再旧也不是故障。
        val h = base(
            needsFrames = false,
            lastFrameAt = 100_000L - 60_000L,
            lastStableAt = 0L,
            lastVisionAt = 0L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `paused or manual never recovers`() {
        val stalled = base(
            paused = true,
            lastFrameAt = 0L,
            lastStableAt = 0L,
            lastVisionAt = 0L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(stalled))
        assertEquals(
            PipelineHealthPolicy.Action.NONE,
            PipelineHealthPolicy.evaluate(
                stalled.copy(manualMode = true, paused = false, needsFrames = false)
            )
        )
    }

    @Test
    fun `never started capture is not treated as a stall`() {
        // 还没收到过任何帧（lastFrameAt=0）说明录屏还没真正开始，不能反复“重建管线”。
        val h = base(lastFrameAt = 0L, lastStableAt = 0L, lastVisionAt = 0L)
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    // ==================== 落子核对停摆 ====================

    @Test
    fun `verifying without any new frame releases the transaction`() {
        val completed = 100_000L
        val h = base(
            now = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS + 1L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 300L,
            landingCompletedAt = completed,
            lastFrameAt = completed - 500L,
            lastStableAt = completed - 500L,
            lastVisionAt = completed - 500L,
        )
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `verifying inside the grace window is left alone`() {
        val completed = 100_000L
        val h = base(
            now = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 300L,
            landingCompletedAt = completed,
            lastFrameAt = completed - 500L,
            lastStableAt = completed - 500L,
            lastVisionAt = completed - 500L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `fresh recognition results keep verification alive`() {
        val completed = 100_000L
        val h = base(
            now = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS + 500L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 300L,
            landingCompletedAt = completed,
            // 还在出识别结果（哪怕核对判定尚未通过），说明链路活着，不该作废这一手。
            lastVisionAt = completed + PipelineHealthPolicy.LANDING_FRAME_GRACE_MS,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `absolute cap ends verification even if results keep coming`() {
        val completed = 100_000L
        val h = base(
            now = completed + PipelineHealthPolicy.LANDING_ABSOLUTE_MS + 1L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = completed - 300L,
            landingCompletedAt = completed,
            lastVisionAt = completed + PipelineHealthPolicy.LANDING_ABSOLUTE_MS,
        )
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `gesture without completion receipt is released by the absolute cap`() {
        val started = 100_000L
        val pending = base(
            now = started + 1_000L,
            landingStage = LandingFlow.Stage.DISPATCHING,
            landingStartedAt = started,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(pending))
        assertEquals(
            PipelineHealthPolicy.Action.REBASE_LANDING,
            PipelineHealthPolicy.evaluate(
                pending.copy(now = started + PipelineHealthPolicy.LANDING_ABSOLUTE_MS + 1L)
            )
        )
    }

    @Test
    fun `landing transaction takes priority over vision recovery`() {
        // 画面也停了、落子也停着：先解决落子（重新建基线），不要同时做两件事。
        val h = base(
            now = 200_000L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = 190_000L,
            landingCompletedAt = 190_500L,
            lastFrameAt = 150_000L,
            lastStableAt = 150_000L,
            lastVisionAt = 150_000L,
        )
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING, PipelineHealthPolicy.evaluate(h))
    }

    // ==================== 取帧/识别侧停摆 ====================

    @Test
    fun `stream without frames rebuilds the capture pipeline`() {
        val h = base(lastFrameAt = 100_000L - PipelineHealthPolicy.STREAM_FRAME_STALL_MS - 1L)
        assertEquals(PipelineHealthPolicy.Action.REBUILD_CAPTURE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `first frames are allowed to fill the stable window`() {
        val firstFrame = 100_000L
        val h = base(
            now = firstFrame + 1_000L,
            lastFrameAt = firstFrame + 1_000L,
            streamStartedAt = firstFrame,
            lastStableAt = 0L,
            lastVisionAt = 0L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `stable window timeout starts after the first frame rather than immediately`() {
        val h = base(
            lastFrameAt = 100_000L,
            lastStableAt = 100_000L - PipelineHealthPolicy.STREAM_STABLE_STALL_MS - 1L,
            lastVisionAt = 0L,
        )
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `stable windows but no recognised board resets vision`() {
        val h = base(
            lastFrameAt = 100_000L,
            lastStableAt = 100_000L,
            lastVisionAt = 100_000L - PipelineHealthPolicy.VISION_STALL_MS - 1L,
        )
        assertEquals(PipelineHealthPolicy.Action.RESET_VISION, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `stuck inference rebuilds the capture pipeline`() {
        val h = base(
            inferenceInFlight = true,
            inferenceStartedAt = 100_000L - PipelineHealthPolicy.INFERENCE_STUCK_MS - 1L,
        )
        assertEquals(PipelineHealthPolicy.Action.REBUILD_CAPTURE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `long running but not stuck inference is not interrupted`() {
        val h = base(
            inferenceInFlight = true,
            inferenceStartedAt = 100_000L - PipelineHealthPolicy.INFERENCE_STUCK_MS + 1L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    // ==================== 限流与非法输入 ====================

    @Test
    fun `recovery is rate limited`() {
        val h = base(
            lastFrameAt = 100_000L,
            lastStableAt = 100_000L - PipelineHealthPolicy.STREAM_STABLE_STALL_MS - 1L,
            lastVisionAt = 0L,
            lastRecoveryAt = 100_000L - PipelineHealthPolicy.MIN_RECOVERY_INTERVAL_MS + 1L,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
        assertEquals(
            PipelineHealthPolicy.Action.RESET_VISION,
            PipelineHealthPolicy.evaluate(
                h.copy(lastRecoveryAt = 100_000L - PipelineHealthPolicy.MIN_RECOVERY_INTERVAL_MS)
            )
        )
    }

    @Test
    fun `invalid clock yields no action`() {
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(base(now = 0L)))
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(base(now = -1L)))
    }

    @Test
    fun `dead capture is not rebuilt forever`() {
        // 用户从通知栏停掉录屏后，MediaProjection 已经无效；此时反复重建没有意义，
        // 应该直接交给用户重新开始，而不是每 2.5 秒重建一次。
        val h = base(
            lastFrameAt = 40_000L,
            lastStableAt = 40_000L,
            lastVisionAt = 40_000L,
            captureAlive = false,
        )
        assertEquals(PipelineHealthPolicy.Action.NONE, PipelineHealthPolicy.evaluate(h))
    }

    @Test
    fun `landing transaction still closes after the capture died`() {
        val h = base(
            now = 100_000L,
            landingStage = LandingFlow.Stage.VERIFYING,
            landingStartedAt = 90_000L,
            landingCompletedAt = 90_500L,
            lastFrameAt = 80_000L,
            lastStableAt = 80_000L,
            lastVisionAt = 80_000L,
            captureAlive = false,
        )
        assertEquals(PipelineHealthPolicy.Action.REBASE_LANDING, PipelineHealthPolicy.evaluate(h))
    }
}

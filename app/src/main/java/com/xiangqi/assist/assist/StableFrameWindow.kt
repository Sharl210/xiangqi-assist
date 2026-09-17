package com.xiangqi.assist.assist

/**
 * 录屏样本稳定窗口（纯 JVM）。
 * 每个样本只比较低分辨率图像签名；连续八个稳定样本只放行一个清晰关键帧。
 * 识别失败不会回填旧棋面，调用方只会在下一窗口重新送样本。
 */
class StableFrameWindow(
    private val requiredStableFrames: Int = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES,
    private val timeoutMs: Long = 2_200L,
) {
    data class Result(
        val ready: Boolean,
        val selected: Frame?,
        val stableCount: Int,
        /** 当前样本与前一稳定窗口样本不一致，窗口已重新开始。 */
        val restartedByChange: Boolean,
    )

    private var epoch: Long? = null
    private var startedAt = 0L
    private var stableCount = 0
    private var lastSignature: FrameStabilityPolicy.Signature? = null
    private var bestFrame: Frame? = null
    private var bestClarity = Double.NEGATIVE_INFINITY

    fun accept(frame: Frame, frameEpoch: Long, now: Long): Result {
        var restartedByChange = false
        if (epoch != frameEpoch || startedAt == 0L || now - startedAt > timeoutMs) {
            resetInternal()
            epoch = frameEpoch
            startedAt = now
        }

        val signature = FrameStabilityPolicy.signature(frame)
        if (lastSignature != null && !FrameStabilityPolicy.isStable(lastSignature, signature)) {
            resetInternal()
            epoch = frameEpoch
            startedAt = now
            restartedByChange = true
        }
        lastSignature = signature
        stableCount++
        val clarity = FrameStabilityPolicy.clarityScore(frame)
        if (clarity >= bestClarity) {
            bestClarity = clarity
            bestFrame = frame
        }
        if (stableCount < requiredStableFrames) {
            return Result(false, null, stableCount, restartedByChange)
        }

        val selected = bestFrame ?: frame
        val count = stableCount
        resetInternal()
        epoch = frameEpoch
        return Result(true, selected, count, restartedByChange)
    }

    fun reset() {
        resetInternal()
        epoch = null
    }

    private fun resetInternal() {
        startedAt = 0L
        stableCount = 0
        lastSignature = null
        bestFrame = null
        bestClarity = Double.NEGATIVE_INFINITY
    }
}

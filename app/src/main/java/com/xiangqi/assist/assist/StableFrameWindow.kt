package com.xiangqi.assist.assist

import java.util.ArrayDeque

/**
 * 录屏样本的连续滑动稳定窗口（纯 JVM）。
 *
 * 每个样本只比较低分辨率图像签名；窗口始终只保留最近的八个样本。
 * 因此当前八个样本不稳定时，下一次只需等一个新的 250ms 样本，丢掉最旧样本后
 * 再检查后面的八个，而不是重新等待八个样本。窗口稳定后只释放一次；画面再次变化
 * 或调用方要求重试时才重新释放，避免静止画面每 250ms 重复跑模型。
 */
class StableFrameWindow(
    private val requiredStableFrames: Int = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES,
    private val timeoutMs: Long = 2_200L,
) {
    data class Result(
        val ready: Boolean,
        val selected: Frame?,
        val stableCount: Int,
        /** 新样本与前一个样本不稳定；样本仍保留在滑动窗口中。 */
        val restartedByChange: Boolean,
    )

    private data class Sample(
        val frame: Frame,
        val signature: FrameStabilityPolicy.Signature,
    )

    private val samples = ArrayDeque<Sample>()
    private var epoch: Long? = null
    private var lastAcceptedAt = Long.MIN_VALUE
    /** 当前连续稳定段已经释放过一个关键帧。 */
    private var releasedForStableRun = false

    init {
        require(requiredStableFrames > 0) { "requiredStableFrames must be positive" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    @Synchronized
    fun accept(frame: Frame, frameEpoch: Long, now: Long): Result {
        val epochChanged = epoch != frameEpoch
        val gapTooLong = lastAcceptedAt != Long.MIN_VALUE && now - lastAcceptedAt > timeoutMs
        if (epochChanged || gapTooLong) {
            clearSamples()
            epoch = frameEpoch
        }
        if (epoch == null) epoch = frameEpoch

        val signature = FrameStabilityPolicy.signature(frame)
        val previous = samples.peekLast()
        val changed = previous != null && !FrameStabilityPolicy.isStable(previous.signature, signature)
        samples.addLast(Sample(frame, signature))
        while (samples.size > requiredStableFrames) samples.removeFirst()
        lastAcceptedAt = now

        val stable = samples.size >= requiredStableFrames && isWindowStable()
        val count = samples.size.coerceAtMost(requiredStableFrames)
        if (!stable) {
            // 只解除“已经释放”的闸门，不清空队列；新样本继续推动窗口前移。
            releasedForStableRun = false
            return Result(false, null, count, changed)
        }

        if (releasedForStableRun) {
            return Result(false, null, requiredStableFrames, changed)
        }

        releasedForStableRun = true
        val selected = samples.maxByOrNull { FrameStabilityPolicy.clarityScore(it.frame) }?.frame ?: frame
        return Result(true, selected, requiredStableFrames, changed)
    }

    /**
     * 模型未能得到可用棋面或结构校验拒绝当前关键帧时调用。
     * 保留最近八帧，让下一个 250ms 样本立即重新尝试，而不是重新等待八帧。
     */
    @Synchronized
    fun rearm() {
        releasedForStableRun = false
    }

    @Synchronized
    fun reset() {
        clearSamples()
        epoch = null
    }

    private fun isWindowStable(): Boolean {
        if (samples.size < requiredStableFrames) return false
        var previous: Sample? = null
        for (sample in samples) {
            if (previous != null && !FrameStabilityPolicy.isStable(previous!!.signature, sample.signature)) {
                return false
            }
            previous = sample
        }
        return true
    }

    private fun clearSamples() {
        samples.clear()
        lastAcceptedAt = Long.MIN_VALUE
        releasedForStableRun = false
    }
}

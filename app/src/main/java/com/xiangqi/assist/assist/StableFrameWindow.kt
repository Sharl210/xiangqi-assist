package com.xiangqi.assist.assist

import java.util.ArrayDeque

/**
 * 录屏样本的连续滑动稳定窗口（纯 JVM）。
 *
 * 每个样本只比较低分辨率图像签名；窗口始终只保留最近的八个样本。
 * 因此当前八个样本不稳定时，下一次只需等一个新的 125ms 样本，丢掉最旧样本后
 * 再检查后面的八个，而不是重新等待八个样本。窗口稳定后只释放一次；画面再次变化
 * 或调用方要求重试时才重新释放，避免静止画面每 125ms 重复跑模型。
 */
class StableFrameWindow(
    private val requiredStableFrames: Int = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES,
    private val timeoutMs: Long = 2_200L,
    /**
     * 静默放行阈值：录屏流用的是 VirtualDisplay，**屏幕内容不变化时它根本不会再投递新帧**。
     * 于是"连续八个样本"这个判据在静止画面上永远无法满足，识别会一次都不发生。
     * 只要最后一张样本之后静默超过这个时长，就说明画面本身没有变化，可以直接放行。
     */
    private val quietReleaseMs: Long = 600L,
    /** 两次静默放行之间的最小间隔，避免拒绝后每 100ms 重复跑一次同一个画面。 */
    private val quietReleaseMinGapMs: Long = 500L,
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
    /** 上一次静默放行时刻；用于限制重复跑同一静止画面。 */
    private var lastQuietReleaseAt = Long.MIN_VALUE

    init {
        require(requiredStableFrames > 0) { "requiredStableFrames must be positive" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    @Synchronized
    fun accept(
        frame: Frame,
        frameEpoch: Long,
        now: Long,
        signatureRegion: DoubleArray? = null,
    ): Result {
        val epochChanged = epoch != frameEpoch
        val gapTooLong = lastAcceptedAt != Long.MIN_VALUE && now - lastAcceptedAt > timeoutMs
        if (epochChanged || gapTooLong) {
            clearSamples()
            epoch = frameEpoch
        }
        if (epoch == null) epoch = frameEpoch

        // 用户框选过识别范围时，签名只覆盖该区域；框外变化不推动稳定窗口。
        val signature = FrameStabilityPolicy.signature(frame, region = signatureRegion)
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
     * 保留最近八帧，让下一个125ms样本立即重新尝试，而不是重新等待八帧。
     */
    @Synchronized
    fun rearm() {
        releasedForStableRun = false
    }

    /** 当前窗口里已积累的样本数（诊断用）。 */
    @Synchronized
    fun sampleCount(): Int = samples.size

    /**
     * 静默放行：屏幕静止时 VirtualDisplay 不再产生新帧，"八个连续样本"永远凑不齐。
     * 只要最后一张样本之后静默超过 [quietReleaseMs]，就用这张已经在窗口里的样本放行。
     *
     * 已积累两张以上样本时仍要求它们彼此稳定（真的没有变化才放行）；
     * 只有一张样本时，"静默"本身就是画面没有变化的证据。
     */
    @Synchronized
    fun releaseWhenQuiet(now: Long): Result {
        val last = samples.peekLast() ?: return Result(false, null, 0, false)
        if (releasedForStableRun) return Result(false, null, samples.size, false)
        if (lastAcceptedAt == Long.MIN_VALUE) return Result(false, null, samples.size, false)
        if (now - lastAcceptedAt < quietReleaseMs) return Result(false, null, samples.size, false)
        if (lastQuietReleaseAt != Long.MIN_VALUE && now - lastQuietReleaseAt < quietReleaseMinGapMs) {
            return Result(false, null, samples.size, false)
        }
        if (samples.size >= 2 && !isWindowStable()) return Result(false, null, samples.size, false)
        releasedForStableRun = true
        lastQuietReleaseAt = now
        return Result(true, last.frame, samples.size.coerceAtMost(requiredStableFrames), false)
    }

    @Synchronized
    fun reset() {
        clearSamples()
        epoch = null
    }

    /**
     * 切换到新的识别代号，并可选保留已经积累的样本。
     *
     * 识别侧自愈（丢弃裁剪网格、整屏重找）改变的只是“下一帧用哪个锚点推理”，
     * 并不改变“画面已经连续稳定”这个既有结论。若在这种自愈里把窗口清空，
     * 需要 1 秒（8×125ms）才能成立的稳定窗口就会被每 1.5 秒一次的自愈反复打断，
     * 整轮管线一次识别都产不出来（真机日志里 `stableAge` 恒为 -1、`VISION_RAW` 为 0 的成因）。
     */
    @Synchronized
    fun adoptEpoch(frameEpoch: Long, preserveSamples: Boolean) {
        if (epoch == frameEpoch) return
        epoch = frameEpoch
        if (!preserveSamples || samples.isEmpty()) {
            clearSamples()
        } else {
            // 保留滑动窗口，只重新打开释放闸门：下一个样本就能参与判定。
            releasedForStableRun = false
        }
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
        lastQuietReleaseAt = Long.MIN_VALUE
    }
}

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
    /**
     * **饿死放行**兜底：画面一直不稳定（对局里的计时、动画、光影变化都可能让稳定窗口永远不闭合），
     * 或者画面完全静止而释放闸门早已关闭，都会让识别长时间一次都不产出。
     * 距离上次放行超过这个时长仍没有新的放行，就强制放行窗口里最新的样本一次。
     *
     * 为什么可以这么做：放行只代表"送模型看一眼"，真正的安全门（双王、棋面结构、
     * 非法位置、合法着法、多帧确认）都在后面，坏帧照样被拒；它换来的是
     * "识别永远不会彻底静默"，看门狗也不会再因为长期没有识别结果而误判成取帧故障、
     * 反复丢弃已经对好的棋盘网格。取 2500ms 是为了兼顾功耗：静止局面下约 0.4 次/秒。
     */
    private val starvationReleaseMs: Long = 2_500L,
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
    /** 上一次真正放行（含稳定窗口放行）的时刻；饿死放行按它计时。 */
    private var lastReleasedAt = Long.MIN_VALUE
    /** 当前这批样本开始收集的时刻；从未放行过时用它作为饿死计时基准。 */
    private var windowStartedAt = Long.MIN_VALUE

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
        if (windowStartedAt == Long.MIN_VALUE) windowStartedAt = now
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
        lastReleasedAt = now
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
     * 静止/停滞画面的兜底放行。
     *
     * 两条路都走这里，缺任何一条识别都会**永久静默**：
     * 1. **静默放行**：屏幕静止时 VirtualDisplay 不再产生新帧，"连续稳定样本"永远凑不齐；
     *    只要最后一张样本之后静默超过 [quietReleaseMs]，就用窗口里最后这张样本放行
     *    （样本 ≥2 张时仍要求彼此稳定，真的没变化才放行）。
     * 2. **饿死放行**：距上次放行超过 [starvationReleaseMs] 仍没有新放行——无论是因为
     *    释放闸门关着（本次稳定段已放行、又没有新样本去清它），还是因为画面一直在变、
     *    稳定窗口永不闭合——都强制放行最新样本一次。安全门在后面，坏帧照样被拒。
     *
     * 限速：闸门关着时的巡检按 [starvationReleaseMs]（静止局面约 0.4 次/秒推理，兼顾功耗）；
     * 闸门开着时是"刚被拒绝/刚进入核对"的重试，按 [quietReleaseMinGapMs] 尽快再试。
     */
    @Synchronized
    fun releaseWhenQuiet(now: Long): Result {
        val last = samples.peekLast() ?: return Result(false, null, 0, false)
        if (lastAcceptedAt == Long.MIN_VALUE) return Result(false, null, samples.size, false)
        val quiet = now - lastAcceptedAt >= quietReleaseMs
        val basis = if (lastReleasedAt != Long.MIN_VALUE) lastReleasedAt else windowStartedAt
        val sinceReleaseOrStart = if (basis == Long.MIN_VALUE) -1L else now - basis
        val starved = sinceReleaseOrStart >= 0L && sinceReleaseOrStart >= starvationReleaseMs
        if (!quiet && !starved) return Result(false, null, samples.size, false)
        // 闸门已经关着（本次稳定段已经放行过）= 这是一次"明知没变化"的巡检，
        // 必须按饿死间隔限速，不能让静止局面每 500ms 跑一次推理。
        // 闸门开着（刚被拒绝、刚进入落子核对）= 这是重试，按最小间隔尽快再试。
        val minGap = if (releasedForStableRun) starvationReleaseMs else quietReleaseMinGapMs
        if (!starved && lastReleasedAt != Long.MIN_VALUE &&
            now - lastReleasedAt < minGap
        ) {
            return Result(false, null, samples.size, false)
        }
        // 静默放行仍要求窗口确实稳定；只有"饿死"这一条才允许在画面持续不稳定时也放行一次，
        // 否则一直变化的画面会让识别永远不产出，看门狗又会误判成取帧故障。
        if (!starved && samples.size >= 2 && !isWindowStable()) {
            return Result(false, null, samples.size, false)
        }
        releasedForStableRun = true
        lastReleasedAt = now
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
        // 清空样本后不存在"上次放行"这个基准；否则新一批样本会被误判成饿死而立刻放行。
        lastReleasedAt = Long.MIN_VALUE
        windowStartedAt = Long.MIN_VALUE
    }
}

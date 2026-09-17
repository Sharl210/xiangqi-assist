package com.xiangqi.assist.assist

/**
 * 录制 → 稳定窗口 → 识别 → 落子核对 这条链路的**独立**健康判定。
 *
 * 与引擎搜索守护（[SearchWatchdogPolicy]）完全分开：
 * - 搜索守护看的是“引擎进程/控制线程/搜索任务还在不在”；
 * - 这里看的是“画面还在不在进来、稳定窗口还在不在成立、识别还在不在出结果、
 *   落子核对还在不在推进”。
 *
 * 为什么要独立：旧的阶段监督只比较“当前阶段停留了多久”，它的时钟来自阶段机自身
 * （`phaseSinceAt`）。可一旦取帧/识别彻底停摆，阶段机就不再被新事件驱动，
 * 用户看到的现象就是“停在计算中/核对中，只能手动暂停再开始”。
 * 判定改用**原始证据时间戳**（最后一帧、最后一次稳定窗口、最后一次识别结果），
 * 不依赖任何展示状态，才能真正兜住这类停摆。
 */
object PipelineHealthPolicy {

    /** 落子核对：手势完成后，多久没有任何新画面就作废这一手事务。 */
    const val LANDING_FRAME_GRACE_MS = 3_500L

    /** 落子核对：不论有没有画面，超过它就一定结束本次事务（绝对上限）。 */
    const val LANDING_ABSOLUTE_MS = 9_000L

    /** 单次模型推理超过它就认为模型线程卡住。 */
    const val INFERENCE_STUCK_MS = 12_000L

    /** 完全收不到录屏样本：录制/编码管线本身停了。 */
    const val STREAM_FRAME_STALL_MS = 2_500L

    /** 收得到样本，但稳定窗口一直不成立（遮挡、动画、定位漂移）。 */
    const val STREAM_STABLE_STALL_MS = 6_000L

    /** 稳定关键帧在出，但一直得不到可用棋面（定位/模型确实认不出）。 */
    const val VISION_STALL_MS = 10_000L

    /** 两次恢复动作之间的最小间隔，避免恢复风暴。 */
    const val MIN_RECOVERY_INTERVAL_MS = 2_500L

    enum class Action {
        /** 一切正常 */
        NONE,

        /** 落子事务无进展：作废这一手并重新建立棋面基线 */
        REBASE_LANDING,

        /** 识别侧停摆但不是录制侧：丢弃裁剪网格、清空稳定窗口、整屏重找 */
        RESET_VISION,

        /** 录制侧停摆：重建 ImageReader/VirtualDisplay，让画面重新进来 */
        REBUILD_CAPTURE,
    }

    /**
     * 判定所需的最小证据集。全部是时间戳与布尔量，便于逐条验证。
     */
    data class Health(
        val paused: Boolean = true,
        val manualMode: Boolean = false,
        /** 当前阶段是否本来就需要读盘；不需要读盘的阶段不该因为“没画面”被恢复 */
        val needsFrames: Boolean = false,
        val landingStage: LandingFlow.Stage? = null,
        val landingStartedAt: Long = 0L,
        val landingCompletedAt: Long = 0L,
        /** 最后一次收到录屏样本的时刻（0 = 尚未收到过） */
        val lastFrameAt: Long = 0L,
        /** 当前这次“连续收到帧”的起始时刻；用于给稳定窗口留出凑帧时间 */
        val streamStartedAt: Long = 0L,
        /** 录制管线创建的时刻；用于区分“还没收到第一帧”和“没有发现棋盘” */
        val captureStartedAt: Long = 0L,
        /** 最后一次稳定窗口成立（准备送模型）的时刻 */
        val lastStableAt: Long = 0L,
        /** 最后一次识别出可用棋面的时刻 */
        val lastVisionAt: Long = 0L,
        val inferenceInFlight: Boolean = false,
        val inferenceStartedAt: Long = 0L,
        /** 录制管线是否还在（MediaProjection + 读取器都还在）；不在就没有可恢复的对象 */
        val captureAlive: Boolean = true,
        /** 上一次恢复动作的时刻，用于限流 */
        val lastRecoveryAt: Long = 0L,
        val now: Long = 0L,
    )

    fun evaluate(h: Health): Action {
        if (h.now <= 0L) return Action.NONE
        if (h.paused || h.manualMode) return Action.NONE

        // 1) 落子事务优先。手势已经派出去了，核对却推不动，这是用户最直接的“卡住”。
        //    录制已经结束时也要能收尾，否则事务会一直挂着。
        if (h.landingStage != null) {
            return if (landingStalled(h)) Action.REBASE_LANDING else Action.NONE
        }

        // 2) 本来就不取帧的阶段（计算中/落子中）：没有画面是设计使然，不恢复。
        if (!h.needsFrames) return Action.NONE
        // 3) 录屏管线已经不在了（用户停了录屏/系统回收）：没有可恢复的对象，
        //    交给用户重新开始，不在这里反复重建。
        if (!h.captureAlive) return Action.NONE
        if (!recoveryAllowed(h)) return Action.NONE

        // 4) 模型线程卡住：重建录制管线，后续帧走新的读取器，卡住的线程被时代淘汰。
        if (h.inferenceInFlight && h.inferenceStartedAt > 0L &&
            h.now - h.inferenceStartedAt > INFERENCE_STUCK_MS
        ) {
            return Action.REBUILD_CAPTURE
        }

        // 5) 录制侧完全没有帧。刚建立的读取器若在超时内没有第一帧，也需要重建；
        //    但在这一时限内必须耐心等待，不能像旧实现一样一看到 lastStableAt=0 就重置。
        if (h.lastFrameAt <= 0L && h.captureStartedAt > 0L &&
            h.now - h.captureStartedAt > STREAM_FRAME_STALL_MS
        ) {
            return Action.REBUILD_CAPTURE
        }
        if (h.lastFrameAt > 0L && h.now - h.lastFrameAt > STREAM_FRAME_STALL_MS) {
            return Action.REBUILD_CAPTURE
        }

        // 6) 有帧，但稳定窗口长期不成立（遮挡/动画/网格漂移）：整屏重找。
        //    只有从第一帧开始已经等待完整窗口时长，才允许触发；首次帧后的凑帧阶段不动。
        if (h.lastFrameAt > 0L &&
            h.lastStableAt <= 0L &&
            h.streamStartedAt > 0L &&
            h.now - h.streamStartedAt > STREAM_STABLE_STALL_MS
        ) {
            return Action.RESET_VISION
        }
        if (h.lastFrameAt > 0L && h.lastStableAt > 0L &&
            h.now - h.lastStableAt > STREAM_STABLE_STALL_MS
        ) {
            return Action.RESET_VISION
        }

        // 7) 稳定关键帧在出，却一直认不出可用棋面：丢弃裁剪网格，整屏重认。
        if (h.lastStableAt > 0L &&
            (h.lastVisionAt <= 0L || h.now - h.lastVisionAt > VISION_STALL_MS)
        ) {
            return Action.RESET_VISION
        }

        return Action.NONE
    }

    /**
     * 落子事务是否已经没有指望。
     *
     * @param h.landingCompletedAt 手势真正结束的时刻；核对阶段以它为主基准，
     *   这样“手势完成 → 画面一直不回来”的情况不会被误当成正常。
     */
    private fun landingStalled(h: Health): Boolean {
        val base = maxOf(h.landingCompletedAt, h.landingStartedAt)
        if (base <= 0L) return false
        // 绝对上限：任何情况下都不能让事务无限期挂着。
        if (h.now - base > LANDING_ABSOLUTE_MS) return true
        // 手势还在派发中（没有完成回执）：只受绝对上限约束。
        if (h.landingStage != LandingFlow.Stage.VERIFYING) return false
        // 核对阶段：只要有新的识别结果回来就说明链路还活着，可以继续等；
        // 完全没有任何新证据超过宽限期，就作废并重新建基线。
        val alive = maxOf(base, h.lastVisionAt)
        return h.now - alive > LANDING_FRAME_GRACE_MS
    }

    private fun recoveryAllowed(h: Health): Boolean =
        h.lastRecoveryAt <= 0L || h.now - h.lastRecoveryAt >= MIN_RECOVERY_INTERVAL_MS
}

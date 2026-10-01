package com.xiangqi.assist.assist

/**
 * Independent health policy for capture → stable-frame accumulation → recognition → landing verify.
 * Timestamps describe real pipeline work, not UI phase transitions.
 */
object PipelineHealthPolicy {
    /** VERIFYING grace after the last genuinely processed/recognized image. */
    const val LANDING_FRAME_GRACE_MS = 875L
    /** Hard cap for a gesture/verification transaction. */
    const val LANDING_ABSOLUTE_MS = 2_250L
    /** A single recognition job may legitimately run this long before the pipeline is rebuilt. */
    const val INFERENCE_STUCK_MS = 3_000L
    /** No ImageReader callbacks: rebuild the capture side. */
    const val STREAM_FRAME_STALL_MS = 625L
    /** Frames arrive but the stable window makes no progress. */
    const val STREAM_STABLE_STALL_MS = 1_500L
    /** No recognition completion (valid or rejected) after stable frames were produced. */
    const val VISION_STALL_MS = 2_500L
    /** 静止 VirtualDisplay 的最长静默保护；超过后按录屏停摆处理，允许重建取帧。 */
    const val STATIC_STREAM_GRACE_MS = 3_000L
    /** Rate limit for destructive pipeline rebuild/reset actions. */
    const val MIN_RECOVERY_INTERVAL_MS = 625L
    /**
     * 取帧重建（REBUILD_CAPTURE）的固定最小间距。
     *
     * 它**不参与**指数退避：退避是给“反复清识别状态但仍有帧”的场景用的；
     * 取帧重建针对的是录屏投递真的停了，这时再等 5 秒只会让画面停更久。
     */
    const val STREAM_REBUILD_INTERVAL_MS = 625L
    /**
     * 连续多少次“重建取帧之后仍然一帧都没收到”就判定本次屏幕共享授权已经无法本地恢复，
     * 改为让用户重新授权，而不是无限重建。
     */
    const val MAX_FRAME_STALL_REBUILDS = 3
    /**
     * 连续多次“恢复之后仍然没有任何一次识别完成”时，恢复间距按 2 倍递增到这个上限。
     *
     * 这是对自激恢复环的硬约束：早先的恢复动作会把“上一次样本/稳定窗口/识别完成”这些
     * 进展时间戳清零，于是下一次看门狗看到的就是“进展已经过期很久”，立刻再恢复一次——
     * 恢复越勤，进展证据越少，判定越像故障。真机日志里整轮 35 秒没有一次识别输出，
     * 却出现 30+ 次 KICK/REBUILD/RESET，就是这条环路。
     */
    const val MAX_RECOVERY_INTERVAL_MS = 5_000L
    /** 画面里确实没有棋盘时的取帧探测间隔；这属于正常待机，不属于管线故障。 */
    const val IDLE_FRAME_PROGRESS_KICK_MS = 1_000L
    /** Global quick probe: poke acquisition after 250ms without a normally processed sample. */
    const val FRAME_PROGRESS_KICK_MS = 250L
    /** Do not queue repeated forced acquisitions faster than the two-frame probe period. */
    const val CAPTURE_KICK_COOLDOWN_MS = 250L

    enum class Action {
        NONE,
        /** A single non-destructive acquireLatestImage probe; the stable-window rule still applies. */
        KICK_CAPTURE,
        REBASE_LANDING,
        RESET_VISION,
        REBUILD_CAPTURE,
        /** 反复重建都取不到帧：本地已无法恢复，需要用户重新授权屏幕共享。 */
        REAUTHORIZE_CAPTURE,
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
        /**
         * 画面里连着识别不到任何棋子，且当前没有可用的裁剪网格：
         * 没有任何“识别状态”需要被清掉，此时破坏性恢复只会打断正在积累的稳定窗口。
         */
        val noBoardIdle: Boolean = false,
        /** 上次破坏性恢复之后，还没有任何一次识别完成的连续次数。 */
        val recoveriesWithoutProgress: Int = 0,
        /** 连续多少次重建取帧之后仍然一帧都没收到。 */
        val frameStallRebuilds: Int = 0,
        /** 本次取帧管线重建后已收到的原始 ImageReader 帧数。 */
        val framesSinceRebuild: Int = 0,
        /** 已经有稳定棋面且当前阶段只是等待棋面变化时，不把“没有新棋面提交”当成视觉故障。 */
        val waitingForBoardChange: Boolean = false,
        val now: Long = 0L,
    )

    fun evaluate(h: Health): Action {
        if (h.now <= 0L || h.paused || h.manualMode) return Action.NONE

        // A dispatched gesture must never be interrupted by capture recovery. Its own clock
        // nevertheless closes a genuinely stuck verify transaction, even if capture died.
        // 落子核对期间由落子专用时钟负责恢复；不能让全局识别/采帧看门狗同时
        // RESET_VISION 或 REBUILD_CAPTURE，否则会清空核对所需的新帧窗口，形成
        // VERIFYING -> RESET_VISION -> 再次超时的卡死环。未超时时必须完全让出控制权。
        if (h.landingStage != null) {
            return if (landingStalled(h)) Action.REBASE_LANDING else Action.NONE
        }
        if (!h.needsFrames || !h.captureAlive) return Action.NONE

        // 屏幕上根本没有棋盘：没有裁剪网格、没有待确认候选，也就没有可恢复的识别状态。
        // 这里只保活取帧（且按待机节奏），绝不 RESET_VISION/REBUILD_CAPTURE——
        // 那只会把正在积累的稳定窗口清掉，让“没棋盘”永远变不成“有棋盘”。
        // 唯一的例外仍是真正的取帧停摆：那时候是录屏侧坏了，必须重建。
        if (h.noBoardIdle) {
            if (h.lastFrameAt > 0L && h.now - h.lastFrameAt > STREAM_FRAME_STALL_MS) {
                return frameStallAction(h)
            }
            val idleBase = maxOf(h.lastProcessedSampleAt, h.streamStartedAt, h.captureStartedAt)
            val idleAge = if (idleBase > 0L) h.now - idleBase else Long.MAX_VALUE
            if (idleAge >= IDLE_FRAME_PROGRESS_KICK_MS && captureKickAllowed(h)) {
                return Action.KICK_CAPTURE
            }
            return Action.NONE
        }

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
            return frameStallAction(h)
        }
        if (h.lastFrameAt > 0L && h.now - h.lastFrameAt > STREAM_FRAME_STALL_MS) {
            // 静止画面可能只产生少量回调；本次管线只要已经收到过原始帧，
            // 就不能再次按“没有新帧”重建。静默画面由稳定窗口和 capturePump 处理。
            if (h.framesSinceRebuild > 0 &&
                h.now - h.lastFrameAt <= STATIC_STREAM_GRACE_MS
            ) return Action.NONE
            return frameStallAction(h)
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

        if (!h.waitingForBoardChange && h.lastStableAt > 0L) {
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

        // 有已确认棋面时，WAITING 阶段的职责只是等待下一次画面变化；
        // 没有新的“棋面提交”并不等于识别链路停摆。帧停摆仍在上面的
        // lastFrameAt 分支处理，真实无帧时依然可以重建录屏管线。
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

    /** 本轮允许的最小恢复间距；连续无进展的恢复按 2 倍递增，上限见 [MAX_RECOVERY_INTERVAL_MS]。 */
    fun recoveryIntervalMs(h: Health): Long {
        var ms = MIN_RECOVERY_INTERVAL_MS
        repeat(h.recoveriesWithoutProgress.coerceIn(0, 8)) {
            ms = (ms * 2).coerceAtMost(MAX_RECOVERY_INTERVAL_MS)
        }
        return ms
    }

    private fun recoveryAllowed(h: Health): Boolean =
        h.lastRecoveryAt <= 0L || h.now - h.lastRecoveryAt >= recoveryIntervalMs(h)

    /** 取帧重建用固定间距，不受指数退避影响（退避只用于 RESET_VISION）。 */
    private fun rebuildAllowed(h: Health): Boolean =
        h.lastRecoveryAt <= 0L || h.now - h.lastRecoveryAt >= STREAM_REBUILD_INTERVAL_MS

    /**
     * 帧停摆时的动作：重建取帧；若连续多次重建后依然一帧都没有，
     * 说明本次屏幕共享已无法本地恢复，改为要求用户重新授权，避免无限重建。
     */
    private fun frameStallAction(h: Health): Action = when {
        !rebuildAllowed(h) -> Action.NONE
        h.frameStallRebuilds >= MAX_FRAME_STALL_REBUILDS -> Action.REAUTHORIZE_CAPTURE
        else -> Action.REBUILD_CAPTURE
    }

    private fun captureKickAllowed(h: Health): Boolean {
        val cooldown = if (h.noBoardIdle) IDLE_FRAME_PROGRESS_KICK_MS else CAPTURE_KICK_COOLDOWN_MS
        return h.lastCaptureKickAt <= 0L || h.now - h.lastCaptureKickAt >= cooldown
    }
}

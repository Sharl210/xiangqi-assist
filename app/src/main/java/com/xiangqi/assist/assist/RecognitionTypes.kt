package com.xiangqi.assist.assist

/** 识别管线的共享类型（纯 JVM，无 Android 依赖）：
 * 帧输入、朝向、单帧识别结果、稳定帧跟踪器。
 *
 * 本文件不包含任何“与上一盘面比较差异格数 / 一或两步合法迁移”的采纳门槛。
 * 结构合法的稳定新盘面一律可以直接确认；画面稳定性由录屏流采样器负责，
 * 棋局结构校验由服务层负责。
 */
class Frame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    /** 画面真正取得的时刻；落子核对只接受晚于手势完成的帧。 */
    val capturedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(argb.size == width * height) { "frame pixel count mismatch" }
    }
}

enum class Orientation {
    /** 红方在下（标准朝向，屏幕下方阵营=红方） */
    STANDARD,
    /** 红方在上（换边/翻转朝向，屏幕下方阵营=黑方） */
    FLIPPED;
}

/**
 * 一帧识别结果。canonical 为项目内部约定（红恒定在 y=9），orientation 表示屏幕朝向。
 */
class RecognitionResult(
    val canonical: Array<IntArray>,
    val screenRaw: Array<IntArray>,
    val orientation: Orientation,
    val issues: List<String>,
    val unknownCells: Int,
    val recognizedPieces: Int,
    val avgScore: Double,
) {
    fun isValid(): Boolean = issues.isEmpty() && unknownCells == 0
}

/**
 * 稳定帧跟踪器：连续出现同一局面才确认。
 *
 * 职责只有三件事：
 * 1. **结构无效**（棋子数量/位置不合法）→ [Event.UNSTABLE]，保持当前确认盘面不变；
 * 2. **尚无确认盘面** → 连续 [confirmCount] 组一致结果后建立首个盘面；
 * 3. **已有确认盘面** → 与当前盘面相同则 [Event.SAME_BOARD]；出现另一个稳定新盘面并由
 *    [confirmCount] 组结果确认后，直接 [Event.NEW_BOARD]。
 *
 * 明确不再做的事（用户要求删除、且曾造成死锁）：
 * - 不再比较“与上一盘面的差异格数”并据此拒收；
 * - 不再用历史棋面关系作为采纳条件；
 * - 不再根据盘面内容自动接管新对局；
 * - 不再从盘面差分推断轮次。轮次由服务层的显式事件维护（自动落子成功、等待对方后看到
 *   新盘面、用户点击“切换走棋方”、重置棋盘）。
 */
class BoardTracker(private val confirmCount: Int = 3) {

    enum class Event {
        /** 稳定新盘面确认 */
        NEW_BOARD,
        /** 与当前确认盘面相同（未变化） */
        SAME_BOARD,
        /** 结构无效或尚未攒够确认帧，等待后续 */
        UNSTABLE,
    }

    /** 当前确认局面 */
    var confirmed: RecognitionResult? = null
        private set

    /**
     * 当前认为的走子方（true=红方走）。
     * 只由 [reset] / [restore] / 服务层显式赋值维护，跟踪器自己不会改它。
     */
    @Volatile var redGo = true

    /** 连续未确认帧数（仅用于提示识别不稳定） */
    @Volatile var unstableStreak = 0
        private set

    /** 与当前确认盘面的差异格数（最近一帧，纯诊断用） */
    @Volatile var lastDiffCells = 0
        private set

    /** 最近一帧为什么还没被采纳（用于状态栏直接告诉用户卡在哪） */
    @Volatile var lastReject: String? = null
        private set

    private var candidate: RecognitionResult? = null
    private var candidateHits = 0

    fun reset(redGoFirst: Boolean = true) {
        confirmed = null
        candidate = null
        candidateHits = 0
        unstableStreak = 0
        lastDiffCells = 0
        lastReject = null
        redGo = redGoFirst
    }

    /** 悔棋/复原：以给定结果与轮次重建跟踪状态，后续帧可正常重新确认 */
    fun restore(result: RecognitionResult, redGoSide: Boolean) {
        confirmed = result
        redGo = redGoSide
        candidate = null
        candidateHits = 0
        unstableStreak = 0
        lastReject = null
    }

    /** 手动模式 / 半自动更新棋谱：直接设定已确认局面（不经过多帧确认） */
    fun forceSet(result: RecognitionResult, redGoSide: Boolean) {
        restore(result, redGoSide)
    }

    fun onFrame(res: RecognitionResult, tolerance: Int = 0): Event {
        val prev = confirmed
        if (!res.isValid()) {
            candidate = null
            candidateHits = 0
            unstableStreak++
            lastReject = "棋局不合法（${res.issues.firstOrNull() ?: "结构异常"}）"
            return Event.UNSTABLE
        }

        if (prev == null) {
            // 首次确认：连续 confirmCount 组一致才确认（瞬时坏帧很难连续多组一致）。
            if (candidate != null && PieceIdentityPolicy.compatibleWithinTolerance(
                    res.canonical, candidate!!.canonical, tolerance
                )) {
                if (tolerance > 0) candidate = res
                candidateHits++
            } else {
                candidate = res
                candidateHits = 1
            }
            if (candidateHits >= confirmCount) {
                confirmed = candidate!!
                candidate = null
                candidateHits = 0
                unstableStreak = 0
                lastReject = null
                return Event.NEW_BOARD
            }
            unstableStreak++
            lastReject = "首次定位中 ${candidateHits}/$confirmCount"
            return Event.UNSTABLE
        }

        val diff = AssistBoard.diffCount(res.canonical, prev.canonical)
        lastDiffCells = diff

        if (diff == 0) {
            candidate = null
            candidateHits = 0
            unstableStreak = 0
            lastReject = null
            return Event.SAME_BOARD
        }

        // 与候选局面一致（容差内）则累计。命中候选时保留原候选（不替换成当前帧），
        // 避免把噪声“漂”进确认结果。
        val matchCand = candidate
        if (matchCand != null && AssistBoard.diffCount(res.canonical, matchCand.canonical) <= tolerance) {
            candidateHits++
        } else {
            candidate = res
            candidateHits = 1
        }

        // 只有“占用格没变、只换了棋子类别”这种不可能是真实走子的抖动，才需要更长的确认窗口。
        val classOnlyChange = PieceIdentityPolicy.isClassOnlyChange(
            prev.canonical, candidate!!.canonical
        )
        val need = PieceIdentityPolicy.requiredFrames(
            legalMove = false, classOnlyChange = classOnlyChange, normal = confirmCount
        )
        if (candidateHits >= need) {
            confirmed = candidate!!
            candidate = null
            candidateHits = 0
            unstableStreak = 0
            lastReject = null
            return Event.NEW_BOARD
        }
        unstableStreak++
        lastReject = "确认中 ${candidateHits}/$need"
        return Event.UNSTABLE
    }

    companion object {
        /** 出现“一步合法着法”或“连续两步”时的快速确认帧数（保留给旧调用方兼容） */
        const val FAST_CONFIRM_FRAMES = 2
    }
}

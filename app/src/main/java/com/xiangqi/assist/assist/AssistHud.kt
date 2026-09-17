package com.xiangqi.assist.assist

import kotlin.math.abs
import kotlin.math.pow

/**
 * 悬浮窗展示逻辑（纯 JVM，便于单元测试）：
 * 1. 阶段判定与短标签（标题、悬浮球、正文都用同一套）；
 * 2. 胜率换算与颜色档（标题后与悬浮球共用）。
 */
object AssistHud {

    /**
     * 当前所处阶段。判定统一在 AssistPhase（表驱动），这里只负责文案与配色。
     * 流程：识盘中 → 计算中 → 待落子 → 落子中 → 等待对面落子中 →（识盘中）…
     * 另外几个是打断流程的非流程态：已暂停 / 手动摆子 / 引擎异常 / 更新棋谱中。
     */
    enum class Stage {
        /** 识盘中：找棋盘、确认局面、等引擎就绪 */
        FINDING,
        /** 更新棋谱中：用户点了「更新棋谱」，正在按需取帧识别 */
        UPDATING,
        /** 计算中：引擎正在搜索 */
        THINKING,
        /** 待落子：算完了、轮到我方，但这一手还没落下去 */
        READY,
        /** 落子中：正在派发落子手势（很短，不读盘） */
        MOVING,
        /** 核对中：手势已结束，正在取帧核对这一手落上没有 */
        VERIFYING,
        /** 等待对面落子中：轮不到我方，盯画面等对手走子 */
        WAITING,
        /** 已暂停（未开始 / 已停止） */
        PAUSED,
        /** 手动摆子 */
        MANUAL,
        /** 引擎异常 */
        ENGINE_ERROR,
    }

    /** 悬浮球上用的极短标签 */
    fun shortLabel(stage: Stage): String = when (stage) {
        Stage.UPDATING -> "更新中"
        Stage.READY -> "待落子"
        Stage.FINDING -> "识盘中"
        Stage.THINKING -> "计算中"
        Stage.MOVING -> "落子中"
        Stage.VERIFYING -> "核对中"
        Stage.WAITING -> "等待对方"
        Stage.PAUSED -> "已暂停"
        Stage.MANUAL -> "手动"
        Stage.ENGINE_ERROR -> "异常"
    }

    /** 面板标题用的标签（可略长一点） */
    fun titleLabel(stage: Stage): String = when (stage) {
        Stage.UPDATING -> "更新棋谱中"
        Stage.READY -> "待落子"
        Stage.FINDING -> "识盘中"
        Stage.THINKING -> "计算中"
        Stage.MOVING -> "落子中"
        Stage.VERIFYING -> "核对落子中"
        Stage.WAITING -> "等待对面落子中"
        Stage.PAUSED -> "已暂停"
        Stage.MANUAL -> "手动摆子"
        Stage.ENGINE_ERROR -> "引擎异常"
    }

    /** 胜率颜色档：高于五成绿、低于五成红、正好五成灰 */
    enum class RateLevel { HIGH, LOW, EVEN }

    /**
     * 胜率（我方视角），[pct] 为两位小数的百分比×100
     * （例如 6325 表示 63.25%）。
     */
    class WinRate(val pct: Int, val level: RateLevel) {
        val text: String get() = formatPct(pct)
    }

    /** 把"百分比×100"的整数格式化成两位小数文本 */
    fun formatPct(pct: Int): String {
        val v = abs(pct)
        val sign = if (pct < 0) "-" else ""
        return "$sign${v / 100}.${"%02d".format(v % 100)}%"
    }

    /**
     * 把引擎给出的 WDL（胜/和/负，**走子方**视角）换算成**红方视角**。
     *
     * UCI 的 wdl 和 score 一样都是"走子方"视角：轮到黑走时，引擎报的"胜"
     * 是黑方胜。直接拿去当红方数据用，就会出现"轮到对手走时胜率翻到对面"。
     */
    fun toRedPerspectiveWdl(
        win: Int,
        draw: Int,
        loss: Int,
        stmIsRed: Boolean,
    ): Triple<Int, Int, Int> =
        if (stmIsRed) Triple(win, draw, loss) else Triple(loss, draw, win)

    /**
     * **优先**用引擎自带的胜/和/负期望算我方胜率（数据来自引擎的 `wdl` 输出，
     * 是它自己 NNUE 算出来的，不是我们换算的）。
     *
     * 入参均为**红方视角**的千分比；[myRed] 表示我方是否执红。
     *
     * 精度要求（用户明确要求提高胜率精度）：
     * 1. 引擎不保证三个数严格相加为 1000，先按非负裁剪再**按总和归一化**，
     *    不能直接除以 1000；
     * 2. 用“和棋算半胜”的期望值 `(win + draw/2) / total`；
     * 3. 全程用 `Double`/`Long` 计算，最后**四舍五入**到万分之一，避免连续整数截断
     *    造成系统性偏低；
     * 4. 三个数全为 0（没有可用数据）时返回 null，交给分数近似路径，不伪造精确值。
     */
    fun winRateFromWdl(
        redWinPermille: Int,
        drawPermille: Int,
        redLossPermille: Int,
        myRed: Boolean,
    ): WinRate? {
        val w = maxOf(0, redWinPermille).toLong()
        val d = maxOf(0, drawPermille).toLong()
        val l = maxOf(0, redLossPermille).toLong()
        val total = w + d + l
        if (total <= 0L) return null
        val redExpected = (w + d / 2.0) / total.toDouble()
        val myExpected = if (myRed) redExpected else 1.0 - redExpected
        val pct = Math.round(myExpected * 10000.0).toInt().coerceIn(0, 10000)
        return WinRate(pct, levelOf(pct))
    }

    /**
     * 开局库统计（胜场 / 和局 / 负场）→ 我方视角胜率。
     *
     * 与 WDL 用同一套精确公式：裁剪负值 → 归一化 → 和棋算半胜 → 四舍五入，
     * 不再用“整数百分比截断”。数据不可用时返回 null。
     */
    fun winRateFromCounts(wins: Int, draws: Int, losses: Int): WinRate? {
        val w = maxOf(0, wins).toLong()
        val d = maxOf(0, draws).toLong()
        val l = maxOf(0, losses).toLong()
        val total = w + d + l
        if (total <= 0L) return null
        val expected = (w + d / 2.0) / total.toDouble()
        val pct = Math.round(expected * 10000.0).toInt().coerceIn(0, 10000)
        return WinRate(pct, levelOf(pct))
    }

    /**
     * 备用：引擎没给 wdl 时，由分数换算我方胜率（logistic）。
     *
     * [redScoreCp] 为红方视角分数（centipawn，正=红优）；[mateIn] 非 null 表示杀棋；
     * [myRed] 表示我方是否执红。公式 `p = 1 / (1 + 10^(-cp/400))`（cp 取我方视角）
     * 是通用的"分数→胜率"近似，仅在没有 wdl 时使用。
     */
    fun winRate(redScoreCp: Int, mateIn: Int?, myRed: Boolean): WinRate {
        val mine = if (myRed) redScoreCp else -redScoreCp
        if (mateIn != null) {
            val iWin = (mateIn > 0) == myRed
            return WinRate(if (iWin) 10000 else 0, if (iWin) RateLevel.HIGH else RateLevel.LOW)
        }
        val p = 1.0 / (1.0 + 10.0.pow(-mine / 400.0))
        val pct = Math.round(p * 10000.0).toInt().coerceIn(0, 10000)
        return WinRate(pct, levelOf(pct))
    }

    /** 无分数时（尚未出招）不显示胜率 */
    fun levelOf(pct: Int): RateLevel = when {
        pct > 5000 -> RateLevel.HIGH
        pct < 5000 -> RateLevel.LOW
        else -> RateLevel.EVEN
    }

    // ==================== 阶段配色（悬浮窗边框 / 悬浮球圆环） ====================

    /** 核对中：青紫（与"落子中"的靛蓝、"待落子"的亮绿都区分开） */
    const val RING_VERIFYING = 0xFF8E24AA.toInt()

    /** 待落子：亮绿（与"落子中"的靛蓝区分开） */
    const val RING_READY = 0xFF7CB342.toInt()

    /** 更新棋谱中：青 */
    const val RING_UPDATING = 0xFF00BCD4.toInt()

    /** 识盘中：琥珀 */
    const val RING_FINDING = 0xFFFFC107.toInt()

    /** 计算中：蓝 */
    const val RING_THINKING = 0xFF2196F3.toInt()

    /** 落子中：靛蓝 */
    const val RING_MOVING = 0xFF3F51B5.toInt()

    /** 等待对面落子中：灰 */
    const val RING_WAITING = 0xFF9E9E9E.toInt()

    /** 手动摆子：青灰 */
    const val RING_MANUAL = 0xFF607D8B.toInt()

    /** 已暂停 / 待命：深灰 */
    const val RING_IDLE = 0xFF757575.toInt()

    /** 引擎异常：红 */
    const val RING_ERROR = 0xFFF44336.toInt()

    /** 每个阶段对应的"外圈颜色"——只用于画边框圆环，不改动整体配色 */
    fun ringColor(stage: Stage): Int = when (stage) {
        Stage.UPDATING -> RING_UPDATING
        Stage.VERIFYING -> RING_VERIFYING
        Stage.READY -> RING_READY
        Stage.FINDING -> RING_FINDING
        Stage.THINKING -> RING_THINKING
        Stage.MOVING -> RING_MOVING
        Stage.WAITING -> RING_WAITING
        Stage.MANUAL -> RING_MANUAL
        Stage.PAUSED -> RING_IDLE
        Stage.ENGINE_ERROR -> RING_ERROR
    }
}

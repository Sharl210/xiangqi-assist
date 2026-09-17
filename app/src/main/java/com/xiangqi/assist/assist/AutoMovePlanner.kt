package com.xiangqi.assist.assist

/**
 * 自动走子的目标定位（纯 JVM，便于单元测试与脱离 Android 环境验证）。
 *
 * 输入：引擎给出的 UCCI 着法 + 当前识别到的棋盘网格 + 屏幕朝向；
 * 输出：该着法起止两点在屏幕上的像素坐标（供无障碍手势点击/滑动使用）。
 *
 * 坐标系约定：
 * - UCCI（如 "h2e2"）按项目内部约定映射为 canonical 坐标：x = 列，y = 行，红方恒在 y=9。
 * - 屏幕坐标：x 向右，y 向下；STANDARD（红在屏幕下方）时 canonical 与屏幕同向，
 *   FLIPPED（红在屏幕上方）时需做中心对称翻转。
 * - BoardGrid 描述的是「9 条纵线 × 10 条横线」交点范围（即第 0 列到第 8 列、
 *   第 0 行到第 9 行的交点），因此第 c 列交点的横坐标 = nx0 + (nx1-nx0) * c / 8。
 */
class AutoMove(
    /** 原始 UCCI 着法 */
    val ucci: String,
    /** 起点屏幕坐标（像素） */
    val fromX: Float,
    val fromY: Float,
    /** 终点屏幕坐标（像素） */
    val toX: Float,
    val toY: Float,
) {
    override fun toString(): String =
        "$ucci (${fromX.toInt()},${fromY.toInt()})->(${toX.toInt()},${toY.toInt()})"
}

object AutoMovePlanner {

    /** 着法落点与网格的一致性下限：格距过小（棋盘被识别得过窄）时不执行，避免点到错误格子 */
    private const val MIN_GRID_PX = 8.0
    /** 落点必须离屏幕边缘至少这么多像素，避免把点击送到系统手势区 */
    private const val EDGE_MARGIN_PX = 2.0

    /**
     * @param pieces canonical 90 子（红恒在 y=9），用于校验起点确实有我方棋子；null 表示不校验
     * @param mySideIsRed 我方是否为红方（用于校验起点棋子归属）
     * @return 可执行着法；任一环节不满足（非法坐标、起点无子、落点在屏外、网格退化）返回 null
     */
    fun plan(
        ucci: String,
        grid: BoardGrid,
        orientation: Orientation,
        frameW: Int,
        frameH: Int,
        pieces: IntArray?,
        mySideIsRed: Boolean,
    ): AutoMove? {
        if (ucci.length < 4) return null
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        if (fx !in 0..8 || fy !in 0..9 || tx !in 0..8 || ty !in 0..9) return null
        if (fx == tx && fy == ty) return null
        if (frameW <= 0 || frameH <= 0) return null

        // 起点必须是我方棋子（轮次错乱 / 幽灵局面时宁可不走）
        if (pieces != null) {
            if (pieces.size < 90) return null
            val p = pieces[fy * 9 + fx]
            if (p == 0) return null
            if (!isMyPiece(p, mySideIsRed)) return null
        }

        val (sCol, sRow) = toScreenCell(fx, fy, orientation)
        val (tCol, tRow) = toScreenCell(tx, ty, orientation)

        val gridW = (grid.nx1 - grid.nx0) * frameW / 8.0
        val gridH = (grid.ny1 - grid.ny0) * frameH / 9.0
        if (gridW < MIN_GRID_PX || gridH < MIN_GRID_PX) return null

        val sx = (grid.nx0 + (grid.nx1 - grid.nx0) * sCol / 8.0) * frameW
        val sy = (grid.ny0 + (grid.ny1 - grid.ny0) * sRow / 9.0) * frameH
        val ex = (grid.nx0 + (grid.nx1 - grid.nx0) * tCol / 8.0) * frameW
        val ey = (grid.ny0 + (grid.ny1 - grid.ny0) * tRow / 9.0) * frameH

        for (v in doubleArrayOf(sx, sy, ex, ey)) {
            if (v.isNaN() || v.isInfinite()) return null
        }
        if (sx < EDGE_MARGIN_PX || sx > frameW - EDGE_MARGIN_PX) return null
        if (ex < EDGE_MARGIN_PX || ex > frameW - EDGE_MARGIN_PX) return null
        if (sy < EDGE_MARGIN_PX || sy > frameH - EDGE_MARGIN_PX) return null
        if (ey < EDGE_MARGIN_PX || ey > frameH - EDGE_MARGIN_PX) return null

        return AutoMove(ucci, sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat())
    }

    /** 把录屏输出坐标缩放到物理屏幕坐标，供无障碍手势使用。 */
    fun scaleToScreen(plan: AutoMove, frameW: Int, frameH: Int, screenW: Int, screenH: Int): AutoMove {
        if (frameW <= 0 || frameH <= 0 || screenW <= 0 || screenH <= 0) return plan
        val sx = screenW.toFloat() / frameW.toFloat()
        val sy = screenH.toFloat() / frameH.toFloat()
        return AutoMove(
            plan.ucci,
            plan.fromX * sx,
            plan.fromY * sy,
            plan.toX * sx,
            plan.toY * sy,
        )
    }


    fun toScreenCell(x: Int, y: Int, orientation: Orientation): Pair<Int, Int> = when (orientation) {
        Orientation.STANDARD -> x to y
        Orientation.FLIPPED -> (8 - x) to (9 - y)
    }

    private fun isMyPiece(piece: Int, mySideIsRed: Boolean): Boolean {
        val red = piece in 1..7
        return red == mySideIsRed
    }

    /**
     * 仿真·随机偏移上限：按棋盘格距的比例。
     * 0.10 格以内——棋子在格子里是圆的，半径约 0.5 格，取 0.10 既看不出"太准"，
     * 又绝不会偏出棋子导致点空（之前 0.18 在格距偏小的棋盘上会点飞）。
     */
    /**
     * 随机偏移幅度：占“格距”的比例。
     *
     * 这个值直接决定点不点得中棋子，**收紧过一次**（0.18 → 0.10 → 现在的 0.05）：
     * - 0.18 时偏移到过格子边缘，实际点击常落到棋子外，是"落子不成功"的重要原因；
     * - 现在 0.05 = 格距的 5%，视觉上仍能看出"不是正中心"，但离格子中心极近，
     *   只要格距换算没问题就一定能点到子。
     *
     * 防作弊并不需要偏移很大：人手下棋本来也会偏，几像素的抖动足够。
     */
    private const val JITTER_FRACTION = 0.05f

    /**
     * 给落点加一点随机偏移（防作弊观感），偏移量限制在格距的一小部分内，
     * 因此仍然落在目标棋子/交点上。仿真模式专用，关闭仿真时不会调用。
     */
    fun applyJitter(
        plan: AutoMove,
        cellW: Float,
        cellH: Float,
        random: java.util.Random,
        fraction: Float = JITTER_FRACTION,
    ): AutoMove {
        // fraction=0 表示不做偏移（等价于关闭仿真），此时原样返回
        if (fraction <= 0f) return plan
        val ax = (cellW * fraction).coerceAtLeast(0f)
        val ay = (cellH * fraction).coerceAtLeast(0f)
        fun j(v: Float, a: Float) = v + (random.nextFloat() * 2f - 1f) * a
        return AutoMove(
            plan.ucci,
            j(plan.fromX, ax), j(plan.fromY, ay),
            j(plan.toX, ax), j(plan.toY, ay),
        )
    }
}

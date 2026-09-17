package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/**
 * 识别结果的**自动修正**（纯 JVM，可单测）。
 *
 * 背景：棋子检测器偶尔会"多认"——同一个棋子被检出两次，或者把棋盘纹理认成马/炮，
 * 于是识别出的局面里出现"3 个马""3 个炮"这种非法结构。
 *
 * 上一版的做法是把**整帧**判为不合法直接丢弃（[AssistBoard.validate] 报错 → 跟踪器计入
 * unstable），结果是：一次误检就让这一帧作废，连续误检就"一直识别不出来"。
 *
 * 现在改为**修正**：超出数量的棋子按**置信度从低到高**剔除（最不可信的先丢），
 * 位置不可能的兵/卒直接丢弃。这样一帧里只有个别误检时仍能得到可用局面。
 *
 * 注意：只在"多认"方向修正；少认（缺子）不补——凭空造子会带来更坏的错误。
 */
object BoardSanitizer {

    /** 各类棋子的合法最大数量 */
    private val MAX_COUNT = mapOf(
        Piece.WSHUAI to 1, Piece.BJIANG to 1,
        Piece.WSHI to 2, Piece.WXIANG to 2, Piece.WMA to 2, Piece.WJU to 2, Piece.WPAO to 2,
        Piece.BSHI to 2, Piece.BXIANG to 2, Piece.BMA to 2, Piece.BJU to 2, Piece.BPAO to 2,
        Piece.WBING to 5, Piece.BZU to 5,
    )

    /** 修正结果 */
    class Repair(
        val board: Array<IntArray>,
        /** 人类可读的修正说明（用于状态栏诊断，空表示没改动） */
        val notes: List<String>,
    ) {
        val changed: Boolean get() = notes.isNotEmpty()
    }

    /**
     * @param board 局面（与 [scores] 同一坐标系）
     * @param scores 每格置信度；缺省时按 0 处理，此时同类棋子按"越靠后越先丢"处理
     */
    fun sanitize(
        board: Array<IntArray>,
        scores: Array<DoubleArray>? = null,
        orientation: Orientation = Orientation.STANDARD,
    ): Repair {
        val h = board.size
        val w = board[0].size
        val out = Array(h) { y -> IntArray(w) { x -> board[y][x] } }
        val notes = mutableListOf<String>()

        fun scoreOf(x: Int, y: Int): Double = scores?.getOrNull(y)?.getOrNull(x) ?: 0.0

        // 位置检查在 canonical 坐标中进行；屏幕翻转时 x/y 都反转，但兵/卒的合法性只依赖 y。
        fun canonicalY(screenY: Int): Int = if (orientation == Orientation.STANDARD) screenY else h - 1 - screenY

        // ---- 1. 位置根本不可能的子：直接丢弃（只在"多认"方向修正，不影响正常棋子） ----
        for (y in 0 until h) for (x in 0 until w) {
            val cy = canonicalY(y)
            when (out[y][x]) {
                Piece.WBING -> if (cy > 6) {
                    out[y][x] = Piece.EMPTY
                    notes.add("丢弃位置不可能的红兵")
                }
                Piece.BZU -> if (cy < 3) {
                    out[y][x] = Piece.EMPTY
                    notes.add("丢弃位置不可能的黑卒")
                }
            }
        }

        // ---- 2. 数量超限：按置信度从低到高剔除 ----
        for ((piece, max) in MAX_COUNT) {
            val cells = mutableListOf<Triple<Int, Int, Double>>()
            for (y in 0 until h) for (x in 0 until w) {
                if (out[y][x] == piece) cells.add(Triple(x, y, scoreOf(x, y)))
            }
            if (cells.size <= max) continue
            // 置信度低的先丢；置信度相同（例如无分数）时丢靠后的，保证结果稳定可复现
            val victims = cells.sortedWith(
                compareBy<Triple<Int, Int, Double>> { it.third }
                    .thenByDescending { it.second * w + it.first }
            ).take(cells.size - max)
            for ((x, y, _) in victims) out[y][x] = Piece.EMPTY
            notes.add("${Piece.getNameByValue(piece)}多了 ${cells.size - max} 个，已剔除")
        }

        return Repair(out, notes)
    }
}

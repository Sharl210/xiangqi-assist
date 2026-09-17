package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Board
import com.xiangqi.assist.gamelogic.Move
import com.xiangqi.assist.gamelogic.Piece
import com.xiangqi.assist.gamelogic.Position
import com.xiangqi.assist.gamelogic.Rule
import kotlin.math.abs

/**
 * 纯 JVM 的中国象棋棋盘抽象。
 * 约定与项目 gamelogic.Board 一致：piece[y][x]，y=0 为黑方底线，y=9 为红方底线（红在下即标准视角）。
 * 本文件不依赖任何 Android 类，便于单元测试。
 */
object AssistBoard {
    const val W = 9
    const val H = 10

    /** 标准开局 FEN（xqbase 格式，红先） */
    const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    /** 标准开局局面，piece[y][x]，红在 y=9 底部 */
    fun canonicalStart(): Array<IntArray> {
        val p = Array(H) { IntArray(W) }
        for (x in 0 until W) {
            p[0][x] = when (x) {
                0, 8 -> Piece.BJU
                1, 7 -> Piece.BMA
                2, 6 -> Piece.BXIANG
                3, 5 -> Piece.BSHI
                else -> Piece.BJIANG
            }
        }
        p[2][1] = Piece.BPAO
        p[2][7] = Piece.BPAO
        for (x in 0 until W step 2) p[3][x] = Piece.BZU
        for (x in 0 until W step 2) p[6][x] = Piece.WBING
        p[7][1] = Piece.WPAO
        p[7][7] = Piece.WPAO
        for (x in 0 until W) {
            p[9][x] = when (x) {
                0, 8 -> Piece.WJU
                1, 7 -> Piece.WMA
                2, 6 -> Piece.WXIANG
                3, 5 -> Piece.WSHI
                else -> Piece.WSHUAI
            }
        }
        return p
    }

    fun clone(p: Array<IntArray>): Array<IntArray> = Array(H) { p[it].clone() }

    fun equal(a: Array<IntArray>, b: Array<IntArray>): Boolean {
        for (y in 0 until H) for (x in 0 until W) if (a[y][x] != b[y][x]) return false
        return true
    }

    /** 两个局面的差异格数（用于识别容错判定与诊断） */
    fun diffCount(a: Array<IntArray>, b: Array<IntArray>): Int {
        var n = 0
        for (y in 0 until H) for (x in 0 until W) if (a[y][x] != b[y][x]) n++
        return n
    }

    /**
     * 判断 next 是否恰好是 prev 走了一步**结构合法**的着法：
     * - 恰好一个起点格被清空、一个终点格出现棋子，且移动的是同一枚子；
     * - 满足中国象棋走法规则（车马炮相仕帅兵的走法与吃子限制）。
     *
     * 用于把「动画残影 / 棋子被遮挡 / 幻觉帧」这类坏帧挡在确认流程之外。
     * 规则模块异常时返回 true（宁可放行，也不因工具异常阻断识别）。
     */
    fun isLegalSingleMove(prev: Array<IntArray>, next: Array<IntArray>): Boolean {
        var fx = -1; var fy = -1; var tx = -1; var ty = -1
        var added = 0; var removed = 0
        for (y in 0 until H) for (x in 0 until W) {
            val o = prev[y][x]
            val n = next[y][x]
            if (o == n) continue
            if (n != Piece.EMPTY) { added++; tx = x; ty = y }
            if (o != Piece.EMPTY && n == Piece.EMPTY) { removed++; fx = x; fy = y }
        }
        if (added != 1 || removed != 1) return false
        if (fx < 0 || fy < 0 || tx < 0 || ty < 0) return false
        val moving = next[ty][tx]
        if (moving == Piece.EMPTY) return false
        if (prev[fy][fx] != moving) return false
        return try {
            val b = Board()
            b.clear()
            for (y in 0 until H) for (x in 0 until W) {
                if (prev[y][x] != Piece.EMPTY) b.setPieceByPosition(x, y, prev[y][x])
            }
            Rule.isValidMove(Move(Position(fx, fy), Position(tx, ty), b), b)
        } catch (t: Throwable) {
            true
        }
    }

    /** piece[y][x] -> xqbase FEN 盘面 + 走子方 */
    fun toFen(pieces: Array<IntArray>, redGo: Boolean): String {
        val sb = StringBuilder()
        for (y in 0 until H) {
            var zeros = 0
            for (x in 0 until W) {
                val p = pieces[y][x]
                if (p == Piece.EMPTY) zeros++
                else {
                    if (zeros > 0) { sb.append(zeros); zeros = 0 }
                    sb.append(Piece.pieceCharMap[p])
                }
            }
            if (zeros > 0) sb.append(zeros)
            if (y < H - 1) sb.append('/')
        }
        return "$sb ${if (redGo) "w" else "b"} - - 0 1"
    }

    fun piecesFromFen(fen: String): Array<IntArray> {
        val p = Array(H) { IntArray(W) }
        val board = fen.trim().split(' ')[0]
        var x = 0
        var y = 0
        for (c in board) {
            when {
                c == '/' -> { x = 0; y++ }
                c in '0'..'9' -> { x += c - '0' }
                else -> { p[y][x] = Piece.pieceValueMap[c] ?: Piece.EMPTY; x++ }
            }
        }
        return p
    }

    /**
     * 应用一个 ucci 着法（如 "h2e2"）到 FEN 局面，返回行棋方翻转后的新 FEN。
     * 着法非法或起点无子时原样返回。
     */    fun applyUcci(fen: String, ucci: String): String {
        if (ucci.length < 4) return fen
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        if (fx !in 0 until W || fy !in 0 until H || tx !in 0 until W || ty !in 0 until H) return fen
        val board = piecesFromFen(fen)
        val piece = board[fy][fx]
        if (piece == Piece.EMPTY) return fen
        board[ty][tx] = piece
        board[fy][fx] = Piece.EMPTY
        val nextRedGo = fen.trim().split(' ').getOrNull(1) != "w"
        return toFen(board, nextRedGo)
    }

    /**
     * 推断刚刚移动的一方：支持普通走子、吃子，以及只有目标格发生替换的识别快照。
     * 同色棋子原地换类不视为走子，避免车/卒/象抖动改变轮次。
     */
    fun movedSide(old: Array<IntArray>, new: Array<IntArray>): Int? {
        var sourcePiece = Piece.EMPTY
        var targetPiece = Piece.EMPTY
        var sources = 0
        var targets = 0
        var replacementOld = Piece.EMPTY
        var replacementNew = Piece.EMPTY
        for (y in 0 until H) for (x in 0 until W) {
            val before = old[y][x]
            val after = new[y][x]
            if (before == after) continue
            if (before != Piece.EMPTY && after == Piece.EMPTY) {
                sources++
                sourcePiece = before
            } else if (after != Piece.EMPTY) {
                targets++
                targetPiece = after
                if (before != Piece.EMPTY) {
                    replacementOld = before
                    replacementNew = after
                }
            }
        }
        if (sources == 1 && targets == 1 && sourcePiece == targetPiece) {
            return if (Piece.isRed(sourcePiece)) 1 else 0
        }
        // 某些识别帧只保留吃子目标格：旧敌子被新棋子替换，起点已在上一帧丢失。
        if (sources == 0 && targets == 1 &&
            replacementOld != Piece.EMPTY && replacementNew != Piece.EMPTY &&
            Piece.isRed(replacementOld) != Piece.isRed(replacementNew)
        ) {
            return if (Piece.isRed(replacementNew)) 1 else 0
        }
        return null
    }

    /** 硬合法性校验：每个阵营的棋子数量必须在合法范围内，帅/将各一。返回问题列表，空表示通过 */
    fun validate(pieces: Array<IntArray>): List<String> {
        val issues = mutableListOf<String>()
        val counts = IntArray(Piece.BZU + 1)
        for (y in 0 until H) for (x in 0 until W) {
            val p = pieces[y][x]
            if (p != Piece.EMPTY) counts[p]++
        }
        if (counts[Piece.WSHUAI] != 1) issues.add("红帅数量异常:${counts[Piece.WSHUAI]}")
        if (counts[Piece.BJIANG] != 1) issues.add("黑将数量异常:${counts[Piece.BJIANG]}")
        val limits = mapOf(2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 5, 9 to 2, 10 to 2, 11 to 2, 12 to 2, 13 to 2, 14 to 5)
        for ((k, v) in limits) {
            if (counts[k] > v) issues.add("${Piece.getNameByValue(k)}数量超限:${counts[k]}")
        }
        var total = 0
        for (c in counts) total += c
        if (total > 32) issues.add("总子数超限:$total")
        return issues
    }

    // ==================== 局面对引擎是否可用（新版引擎校验很严） ====================
    //
    // 皮卡鱼 2026 版起对局面做严格校验：只要"走子方的王可以被吃"或"将帅数量异常"
    // 这类问题，引擎会打印 CRITICAL ERROR 并**直接终止进程**。
    // 识别难免出错，所以发送前必须自己先判一遍，不能把非法局面丢给引擎。

    /** 找某一方的王坐标；找不到返回 null */
    private fun findKing(pieces: Array<IntArray>, red: Boolean): Pair<Int, Int>? {
        val target = if (red) Piece.WSHUAI else Piece.BJIANG
        for (y in 0 until H) for (x in 0 until W) {
            if (pieces[y][x] == target) return x to y
        }
        return null
    }

    private fun inBoard(x: Int, y: Int): Boolean = x in 0 until W && y in 0 until H

    /**
     * 某一方的王是否**正被对方棋子攻击**（含将帅照面）。
     *
     * 这是自己实现的判定，没有沿用游戏内的 [Rule.isJiangShuaiInDanger]——
     * 后者的坐标/调用语义与这里的用法不一致（实测对同样局面给出相反结论）。
     *
     * 只实现"能否攻击到王"所必需的部分：车、炮、马、兵/卒、飞将。
     * 仕/相 走不出自己的半场，够不到对方的王，无需判断。
     */
    fun kingAttacked(pieces: Array<IntArray>, red: Boolean): Boolean {
        val king = findKing(pieces, red) ?: return true // 没有王本身就是非法局面
        val (kx, ky) = king
        val foeIsRed = !red
        fun isFoe(p: Int): Boolean = p != Piece.EMPTY && Piece.isRed(p) == foeIsRed

        // ---- 车 / 炮：沿四条直线 ----
        val rook = if (foeIsRed) Piece.WJU else Piece.BJU
        val cannon = if (foeIsRed) Piece.WPAO else Piece.BPAO
        for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            var x = kx + dx
            var y = ky + dy
            var screen = 0 // 已经遇到的挡子数
            while (inBoard(x, y)) {
                val p = pieces[y][x]
                if (p != Piece.EMPTY) {
                    if (screen == 0 && isFoe(p) && p == rook) return true
                    if (screen == 1 && isFoe(p) && p == cannon) return true
                    screen++
                    if (screen >= 2) break // 已有两子，更远处再接炮也打不到王
                }
                x += dx; y += dy
            }
        }

        // ---- 马：八个落点，逐个检查马腿 ----
        val horse = if (foeIsRed) Piece.WMA else Piece.BMA
        for ((dx, dy) in listOf(
            -1 to -2, 1 to -2, -1 to 2, 1 to 2,
            -2 to -1, 2 to -1, -2 to 1, 2 to 1,
        )) {
            val hx = kx + dx
            val hy = ky + dy
            if (!inBoard(hx, hy) || pieces[hy][hx] != horse) continue
            // 马腿 = 从马出发、沿"长边"方向走一格的那个点
            val tx = kx - hx
            val ty = ky - hy
            val legX: Int
            val legY: Int
            if (abs(tx) == 2) { legX = hx + tx / 2; legY = hy } else { legX = hx; legY = hy + ty / 2 }
            if (inBoard(legX, legY) && pieces[legY][legX] == Piece.EMPTY) return true
        }

        // ---- 兵 / 卒 ----
        if (foeIsRed) {
            // 红兵向上（y 减小）：在王的下一格正对即可吃；过河后（y<=4）还能横向吃
            val by = ky + 1
            if (inBoard(kx, by) && pieces[by][kx] == Piece.WBING) return true
            if (ky <= 4) {
                if (inBoard(kx - 1, ky) && pieces[ky][kx - 1] == Piece.WBING) return true
                if (inBoard(kx + 1, ky) && pieces[ky][kx + 1] == Piece.WBING) return true
            }
        } else {
            // 黑卒向下（y 增大）
            val by = ky - 1
            if (inBoard(kx, by) && pieces[by][kx] == Piece.BZU) return true
            if (ky >= 5) {
                if (inBoard(kx - 1, ky) && pieces[ky][kx - 1] == Piece.BZU) return true
                if (inBoard(kx + 1, ky) && pieces[ky][kx + 1] == Piece.BZU) return true
            }
        }
        return false
    }

    /** 将帅是否照面（同一列且中间无子）——非法局面 */
    fun kingsFacing(pieces: Array<IntArray>): Boolean {
        val r = findKing(pieces, true) ?: return false
        val b = findKing(pieces, false) ?: return false
        if (r.first != b.first) return false
        val x = r.first
        val lo = minOf(r.second, b.second) + 1
        val hi = maxOf(r.second, b.second) - 1
        for (y in lo..hi) if (pieces[y][x] != Piece.EMPTY) return false
        return true
    }

    /**
     * 棋子是否站在了它不可能到达的位置（引擎也会拒绝这类局面）。
     * 目前覆盖实测过的兵/卒越界：红兵只能在 y0..y6，黑卒只能在 y3..y9。
     */
    /**
     * 这一步在**我方模型**里是不是合法着法（用项目自带的规则引擎判）。
     *
     * 用途：落子前最后拦一道。引擎/开局库给出的着法是针对"我们以为的棋面"算的；
     * 万一这个棋面与真实棋面有出入，这一步打到游戏里就会被判"走子不符合规范"，
     * 白点一次、还引起注意。与其让它打出去，不如拦下来、重新识别。
     *
     * 规则引擎本身异常时返回 true（不拦），避免把功能整体卡死。
     */
    fun isLegalMoveInModel(pieces: IntArray, ucci: String, mySideIsRed: Boolean): Boolean {
        if (ucci.length < 4 || pieces.size < 90) return false
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        if (fx !in 0..8 || fy !in 0..9 || tx !in 0..8 || ty !in 0..9) return false
        if (fx == tx && fy == ty) return false
        val p = pieces[fy * 9 + fx]
        if (p == Piece.EMPTY) return false
        if (Piece.isRed(p) != mySideIsRed) return false
        return try {
            val b = Board()
            b.clear()
            for (y in 0 until H) for (x in 0 until W) {
                val v = pieces[y * 9 + x]
                if (v != Piece.EMPTY) b.setPieceByPosition(x, y, v)
            }
            Rule.isValidMove(Move(Position(fx, fy), Position(tx, ty), b), b)
        } catch (t: Throwable) {
            true
        }
    }

    fun invalidPiecePlacement(pieces: Array<IntArray>): String? {
        // 下面这些"合法落点"不是推的，是拿真实引擎逐个试出来的
        // （每个点位都单独喂给 Pikafish 2026-09-06 看它是 ACCEPT 还是 REJECT）。

        // 帅/将：九宫内任意交叉点（x 3..5，y 7..9 / 0..2）
        fun inRedPalace(x: Int, y: Int) = x in 3..5 && y in 7..9
        fun inBlackPalace(x: Int, y: Int) = x in 3..5 && y in 0..2

        // 仕/士：九宫内 5 个斜线点（不是九宫全部 9 个点）
        val redAdvisor = setOf(3 to 9, 5 to 9, 4 to 8, 3 to 7, 5 to 7)
        val blackAdvisor = setOf(3 to 0, 5 to 0, 4 to 1, 3 to 2, 5 to 2)

        // 相/象：己方 7 个点（含两个起点），走田字可达的那些
        val redElephant = setOf(2 to 9, 6 to 9, 0 to 7, 4 to 7, 8 to 7, 2 to 5, 6 to 5)
        val blackElephant = setOf(2 to 0, 6 to 0, 0 to 2, 4 to 2, 8 to 2, 2 to 4, 6 to 4)

        for (y in 0 until H) for (x in 0 until W) {
            when (val p = pieces[y][x]) {
                Piece.EMPTY -> {}
                Piece.WSHUAI -> if (!inRedPalace(x, y)) return "红帅不在九宫内（识别有误）"
                Piece.BJIANG -> if (!inBlackPalace(x, y)) return "黑将不在九宫内（识别有误）"
                Piece.WSHI -> if ((x to y) !in redAdvisor) return "红仕不在合法点位上（识别有误）"
                Piece.BSHI -> if ((x to y) !in blackAdvisor) return "黑士不在合法点位上（识别有误）"
                Piece.WXIANG -> if ((x to y) !in redElephant) return "红相不在合法点位上（识别有误）"
                Piece.BXIANG -> if ((x to y) !in blackElephant) return "黑象不在合法点位上（识别有误）"
                Piece.WBING -> if (y > 6) return "红兵出现在不可能的位置（识别有误）"
                Piece.BZU -> if (y < 3) return "黑卒出现在不可能的位置（识别有误）"
                else -> if (!Piece.isValid(p)) return "识别出未知棋子（识别有误）"
            }
        }
        return null
    }

    /**
     * 这个局面能否安全发给引擎。
     * 返回 null 表示可用；否则返回"不可用的原因"（可直接显示给用户）。
     *
     * **判据全部来自对皮卡鱼 2026-09-06 的实测**（每个 FEN 都真喂给引擎看过结果）。
     * 最反直觉的一条：引擎拒收的唯一规律是「走子方能够立即吃掉对方的王」——
     *   轮红走 + 黑将可被红方吃  → 拒绝（黑方上一步没应将）
     *   轮红走 + 红帅被黑方吃    → 通过（红方正被将军，本来就要应将）
     * 所以只检查**非走子方**的王；若反过来查"走子方被将军"，会把合法局面错杀，
     * 导致被将军时反而给不出建议。
     */
    fun engineUnsafeReason(pieces: Array<IntArray>, redGo: Boolean): String? {
        val issues = validate(pieces)
        if (issues.isNotEmpty()) return issues.first()
        if (findKing(pieces, true) == null) return "未识别到红帅"
        if (findKing(pieces, false) == null) return "未识别到黑将"
        if (kingsFacing(pieces)) return "将帅照面（局面识别有误）"
        invalidPiecePlacement(pieces)?.let { return it }
        // 非走子方的王被攻击 = 走子方可以立刻吃王 = 引擎判定为非法局面
        if (kingAttacked(pieces, !redGo)) return "非当前走子方王被将军（局面识别有误）"
        return null
    }

}

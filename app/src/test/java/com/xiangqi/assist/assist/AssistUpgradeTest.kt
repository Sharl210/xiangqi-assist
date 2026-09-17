package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本次升级的三块新能力验证（纯 JVM，不依赖 Android 运行时）：
 * 1. 识别健壮性：网格锚点兜底 + 容错确认 + 结构校验收敛（不再有“非法跳变”拒绝门）；
 * 2. 自动走子的落点换算（AutoMovePlanner）；
 * 3. 走法结构性合法性判定（AssistBoard.isLegalSingleMove）。
 */
class AssistUpgradeTest {

    // ---------------- 公共夹具 ----------------

    private val frameW = 1080
    private val frameH = 1920
    private val gx0 = 100.0
    private val gy0 = 300.0
    private val gx1 = 900.0
    private val gy1 = 1200.0
    private val cellW = (gx1 - gx0) / 8.0
    private val cellH = (gy1 - gy0) / 9.0

    private fun grid() = BoardGrid.fromPxCorners(gx0, gy0, gx1, gy1, frameW, frameH)

    private fun labelOf(piece: Int): Int = YoloDetection.PIECE_CODES.indexOf(piece)

    private fun det(piece: Int, col: Double, row: Double) = YoloDetection(
        labelOf(piece), 0.9,
        gx0 + col * cellW, gy0 + row * cellH, cellW * 0.6, cellH * 0.6)

    /** 按 canonical 布局生成检测（canonical 行 == 屏幕行，用于 STANDARD 朝向） */
    private fun detsFrom(board: Array<IntArray>): List<YoloDetection> {
        val out = ArrayList<YoloDetection>()
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val p = board[y][x]
            if (p != Piece.EMPTY) out.add(det(p, x.toDouble(), y.toDouble()))
        }
        return out
    }

    private fun res(board: Array<IntArray>): RecognitionResult {
        var n = 0
        for (row in board) for (v in row) if (v != Piece.EMPTY) n++
        return RecognitionResult(
            AssistBoard.clone(board), AssistBoard.clone(board),
            Orientation.STANDARD, emptyList(), 0, n, 0.9)
    }

    /** 开局 + 红炮二平五（h2e2）：一步合法着法 */
    private fun movedBoard(): Array<IntArray> {
        val b = AssistBoard.canonicalStart()
        b[7][4] = b[7][7]
        b[7][7] = Piece.EMPTY
        return b
    }

    // ---------------- 1. 识别健壮性 ----------------

    @Test
    fun `anchor hint rescues frames whose board box was missed`() {
        // 8 子残局、模型没检出 board 框：旧实现子数不足 20 会直接返回 null
        val board = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        board[9][4] = Piece.WSHUAI
        board[9][3] = Piece.WSHI
        board[9][5] = Piece.WSHI
        board[5][4] = Piece.WMA
        board[0][4] = Piece.BJIANG
        board[2][4] = Piece.BXIANG
        board[4][6] = Piece.BXIANG
        board[6][6] = Piece.BZU
        val dets = detsFrom(board)

        // 无锚点：子数不够，映射失败
        assertNull(DetectionBoardMapper.map(dets, frameW, frameH))

        // 有锚点（上一帧的网格）：沿用锚点成功映射
        val anchor = DetectionBoardMapper.AnchorHint(gx0, gy0, gx1, gy1)
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH, anchor = anchor)
        assertNotNull(mapped)
        assertEquals(8, mapped!!.pieceCount)
        assertEquals(DetectionBoardMapper.AnchorSource.PREVIOUS_GRID, mapped.anchorSource)
        assertTrue(AssistBoard.equal(board, mapped.canonical))
    }

    @Test
    fun `board box still wins over anchor when both present`() {
        val board = AssistBoard.canonicalStart()
        val boardDet = YoloDetection(
            YoloDetection.LABEL_BOARD, 0.95,
            (gx0 + gx1) / 2, (gy0 + gy1) / 2, gx1 - gx0, gy1 - gy0)
        val dets = detsFrom(board) + boardDet
        val anchor = DetectionBoardMapper.AnchorHint(0.0, 0.0, 200.0, 200.0)
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH, anchor = anchor)
        assertNotNull(mapped)
        assertEquals(DetectionBoardMapper.AnchorSource.BOARD_BOX, mapped!!.anchorSource)
        assertTrue(AssistBoard.equal(board, mapped.canonical))
    }

    @Test
    fun `tolerance confirms board despite single cell jitter`() {
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val start = AssistBoard.canonicalStart()
        repeat(3) { tracker.onFrame(res(start), 1) }

        val mid = movedBoard()
        // 两帧之间夹一帧"某一格误检"的抖动帧（容忍 1 格差）：候选仍是干净的 mid，
        // 攒够确认帧后确认的是 mid，而不是带噪声的那一帧。
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid), 1))
        val jitter = AssistBoard.clone(mid)
        jitter[0][0] = Piece.BMA // 角上车被误检成马（单格噪声，未换类别）
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(jitter), 1))
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(mid), 1))
        assertTrue(AssistBoard.equal(mid, tracker.confirmed!!.canonical))
    }

    @Test
    fun `strict mode without tolerance still requires identical frames`() {
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val start = AssistBoard.canonicalStart()
        repeat(3) { tracker.onFrame(res(start), 0) }

        val mid = movedBoard()
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid), 0))
        val jitter = AssistBoard.clone(mid)
        jitter[0][0] = Piece.BMA
        // 无容错：抖动帧让候选重置，确认被推迟并重新起算
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(jitter), 0))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid), 0))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid), 0))
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(mid), 0))
    }

    @Test
    fun `structurally valid change is confirmed after the normal window`() {
        // 单格差异（凭空少一个子）只要棋局结构合法，就不再被任何“非法跳变”门槛拦住；
        // 它只需要走完正常的确认窗口。
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val start = AssistBoard.canonicalStart()
        repeat(3) { tracker.onFrame(res(start), 0) }

        val ghost = AssistBoard.clone(start)
        ghost[0][1] = Piece.EMPTY
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(ghost), 0))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(ghost), 0))
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(ghost), 0))
        assertTrue(AssistBoard.equal(ghost, tracker.confirmed!!.canonical))
    }

    @Test
    fun `multi cell change is accepted instead of dead locking the recognizer`() {
        // 旧实现把“三格同时变化”当成非法跳变永久拒绝，最终把整个识别循环锁死。
        // 现在只要求结构合法 + 连续确认帧。
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val start = AssistBoard.canonicalStart()
        repeat(3) { tracker.onFrame(res(start), 1) }

        val ghost = AssistBoard.clone(start)
        ghost[0][1] = Piece.EMPTY
        ghost[0][7] = Piece.EMPTY
        var confirmed = -1
        for (i in 1..10) {
            if (tracker.onFrame(res(ghost), 1) == BoardTracker.Event.NEW_BOARD) {
                confirmed = i
                break
            }
        }
        assertTrue("结构合法的盘面必须能在有限帧内被采纳", confirmed > 0)
        assertTrue(AssistBoard.equal(ghost, tracker.confirmed!!.canonical))
    }

    @Test
    fun `structurally invalid board never overwrites the baseline`() {
        // 结构校验仍然保留：帅将缺失这种真正非法的盘面一律不写入。
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val start = AssistBoard.canonicalStart()
        repeat(3) { tracker.onFrame(res(start), 1) }

        val broken = AssistBoard.clone(start)
        broken[9][4] = Piece.EMPTY
        repeat(20) {
            assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(invalid(broken), 1))
        }
        assertTrue(AssistBoard.equal(start, tracker.confirmed!!.canonical))
    }

    @Test
    fun `legal single move is recognised`() {
        val start = AssistBoard.canonicalStart()
        val mid = movedBoard()
        assertTrue(AssistBoard.isLegalSingleMove(start, mid))
        // 同一局面不算走子
        assertFalse(AssistBoard.isLegalSingleMove(start, AssistBoard.clone(start)))
    }

    @Test
    fun `illegal single move such as rook jumping over a piece is rejected`() {
        val start = AssistBoard.canonicalStart()
        val bad = AssistBoard.clone(start)
        // 车 a0 -> a5 中间隔着兵，属非法着法
        bad[4][0] = bad[9][0]
        bad[9][0] = Piece.EMPTY
        assertFalse(AssistBoard.isLegalSingleMove(start, bad))
    }

    /** 与 res 相同，但强制标记为结构无效 */
    private fun invalid(board: Array<IntArray>): RecognitionResult = RecognitionResult(
        board, board, Orientation.STANDARD, listOf("模拟结构非法"), 0,
        board.sumOf { row -> row.count { it != Piece.EMPTY } }, 0.9)

    // ---------------- 2. 自动走子落点换算 ----------------

    @Test
    fun `planner maps standard orientation to screen pixels`() {
        val pieces = IntArray(90)
        pieces[9 * 9 + 0] = Piece.WJU // canonical (0,9)
        val plan = AutoMovePlanner.plan("a0a1", grid(), Orientation.STANDARD, frameW, frameH, pieces, true)
        assertNotNull(plan)
        assertEquals(gx0.toFloat(), plan!!.fromX, 0.5f)
        assertEquals(gy1.toFloat(), plan.fromY, 0.5f)
        assertEquals(gx0.toFloat(), plan.toX, 0.5f)
        assertEquals((gy0 + cellH * 8).toFloat(), plan.toY, 0.5f)
    }

    @Test
    fun `planner mirrors coordinates when screen is flipped`() {
        val pieces = IntArray(90)
        pieces[9 * 9 + 0] = Piece.WJU // canonical (0,9)
        val plan = AutoMovePlanner.plan("a0a1", grid(), Orientation.FLIPPED, frameW, frameH, pieces, true)
        assertNotNull(plan)
        // 翻转朝向：canonical (0,9) 落在屏幕右上角
        assertEquals(gx1.toFloat(), plan!!.fromX, 0.5f)
        assertEquals(gy0.toFloat(), plan.fromY, 0.5f)
        assertEquals(gx1.toFloat(), plan.toX, 0.5f)
        assertEquals((gy0 + cellH).toFloat(), plan.toY, 0.5f)
    }

    @Test
    fun `planner refuses move whose origin holds no own piece`() {
        // 起点是空格
        val empty = IntArray(90)
        assertNull(AutoMovePlanner.plan("a0a1", grid(), Orientation.STANDARD, frameW, frameH, empty, true))
        // 起点是对方棋子
        val enemy = IntArray(90)
        enemy[9 * 9 + 0] = Piece.BJU
        assertNull(AutoMovePlanner.plan("a0a1", grid(), Orientation.STANDARD, frameW, frameH, enemy, true))
    }

    @Test
    fun `planner refuses malformed moves and degenerate grids`() {
        val pieces = IntArray(90)
        pieces[9 * 9 + 0] = Piece.WJU
        assertNull(AutoMovePlanner.plan("zz", grid(), Orientation.STANDARD, frameW, frameH, pieces, true))
        assertNull(AutoMovePlanner.plan("a0a0", grid(), Orientation.STANDARD, frameW, frameH, pieces, true))
        // 棋盘框退化成一条线：格距过小，落点不可信
        val tiny = BoardGrid.fromPxCorners(0.0, 0.0, 40.0, 45.0, frameW, frameH)
        assertNull(AutoMovePlanner.plan("a0a1", tiny, Orientation.STANDARD, frameW, frameH, pieces, true))
    }

    @Test
    fun `screen cell mapping round trips for both orientations`() {
        for (x in 0 until 9) for (y in 0 until 10) {
            val (sx, sy) = AutoMovePlanner.toScreenCell(x, y, Orientation.STANDARD)
            assertEquals(x, sx); assertEquals(y, sy)
            val (fx, fy) = AutoMovePlanner.toScreenCell(x, y, Orientation.FLIPPED)
            assertEquals(8 - x, fx); assertEquals(9 - y, fy)
            // 翻转两次回到自身
            val (bx, by) = AutoMovePlanner.toScreenCell(fx, fy, Orientation.FLIPPED)
            assertEquals(x, bx); assertEquals(y, by)
        }
    }

    // ---------------- 3. root 启用列表合并（纯逻辑） ----------------

    @Test
    fun `mergeEnabledList appends without dropping existing services`() {
        val existing = "com.foo/.SvcA:com.bar/.SvcB"
        val merged = com.xiangqi.assist.assist.access.RootHelper.mergeEnabledList(existing, "com.xiangqi.assist/.S")
        assertEquals("com.foo/.SvcA:com.bar/.SvcB:com.xiangqi.assist/.S", merged)
        // 幂等：重复合并不产生重复项
        val again = com.xiangqi.assist.assist.access.RootHelper.mergeEnabledList(merged, "com.xiangqi.assist/.S")
        assertEquals(merged, again)
        // 空列表也能正常追加
        assertEquals("com.xiangqi.assist/.S",
            com.xiangqi.assist.assist.access.RootHelper.mergeEnabledList("", "com.xiangqi.assist/.S"))
    }
}

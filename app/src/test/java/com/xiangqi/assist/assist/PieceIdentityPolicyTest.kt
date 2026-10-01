package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceIdentityPolicyTest {
    @Test
    fun `rook misread as pawn is a class-only change`() {
        val old = AssistBoard.canonicalStart()
        val wrong = AssistBoard.clone(old)
        wrong[9][0] = Piece.WBING
        assertTrue(PieceIdentityPolicy.isClassOnlyChange(old, wrong))
        assertEquals(1, PieceIdentityPolicy.replacementCount(old, wrong))
        assertEquals(
            PieceIdentityPolicy.CLASS_CHANGE_CONFIRM_FRAMES,
            PieceIdentityPolicy.requiredFrames(false, true, 2),
        )
    }

    @Test
    fun `class labels are never hidden by one-cell tolerance`() {
        val rook = AssistBoard.canonicalStart()
        val elephant = AssistBoard.clone(rook)
        elephant[9][0] = Piece.WXIANG
        assertFalse(PieceIdentityPolicy.compatibleWithinTolerance(rook, elephant, tolerance = 1))
    }

    @Test
    fun `real move is not a class-only change`() {
        val old = AssistBoard.canonicalStart()
        val moved = AssistBoard.clone(old)
        moved[9][1] = Piece.EMPTY
        moved[7][2] = Piece.WMA
        assertFalse(PieceIdentityPolicy.isClassOnlyChange(old, moved))
        assertEquals(BoardTracker.FAST_CONFIRM_FRAMES,
            PieceIdentityPolicy.requiredFrames(true, false, 3))
    }

    @Test
    fun `misclassified moved piece is restored from its source identity`() {
        val old = AssistBoard.canonicalStart()
        val observed = AssistBoard.clone(old)
        observed[7][7] = Piece.EMPTY
        observed[7][4] = Piece.WBING // same-color target misread instead of red cannon
        val repaired = PieceIdentityPolicy.repairMovedPiece(old, observed)
        assertNotNull(repaired)
        assertEquals(Piece.WPAO, repaired!![7][4])
        assertEquals(Piece.EMPTY, repaired[7][7])
    }

    @Test
    fun `tracker resists transient car pawn elephant oscillation`() {
        val tracker = BoardTracker(confirmCount = 2)
        val start = AssistBoard.canonicalStart()
        val base = result(start)
        tracker.onFrame(base)
        tracker.onFrame(base)
        assertTrue(AssistBoard.equal(start, tracker.confirmed!!.canonical))

        // 来回抖动（车↔兵↔相）永远攒不够“同格换字”所需的更长确认窗口
        repeat(5) { i ->
            val noisy = AssistBoard.clone(start)
            noisy[9][0] = if (i % 2 == 0) Piece.WBING else Piece.WXIANG
            assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(result(noisy), tolerance = 1))
        }
        assertTrue(AssistBoard.equal(start, tracker.confirmed!!.canonical))
    }

    @Test
    fun `persistent identical class reading is confirmed after the long window`() {
        // 稳定重复出现的同一读法不再被永久拒绝：走完“同格换字”的长确认窗口后采纳。
        // （拒绝门会造成死锁，用户已明确要求删除；这里只保留“抖动需要更长窗口”。）
        val tracker = BoardTracker(confirmCount = 2)
        val start = AssistBoard.canonicalStart()
        val base = result(start)
        tracker.onFrame(base)
        tracker.onFrame(base)

        val stableWrong = AssistBoard.clone(start)
        stableWrong[9][0] = Piece.WBING
        var confirmed = -1
        for (i in 1..20) {
            if (tracker.onFrame(result(stableWrong), tolerance = 1) == BoardTracker.Event.NEW_BOARD) {
                confirmed = i
                break
            }
        }
        assertEquals(PieceIdentityPolicy.CLASS_CHANGE_CONFIRM_FRAMES, confirmed)
        assertTrue(AssistBoard.equal(stableWrong, tracker.confirmed!!.canonical))
    }

    @Test
    fun `rule-provable move is confirmed one frame earlier than an unprovable change`() {
        // 这是本轮提速的核心：能被规则引擎证明的一步合法着法走快确认，
        // 不能证明的变化仍走普通门——快慢由证据决定，不由“敢不敢”决定。
        val start = AssistBoard.canonicalStart()
        val moved = AssistBoard.clone(start)
        moved[9][1] = Piece.EMPTY
        moved[7][2] = Piece.WMA
        assertTrue(AssistBoard.isLegalSingleMove(start, moved))

        val fast = BoardTracker(confirmCount = 3)
        repeat(3) { fast.onFrame(result(start)) }
        assertEquals(BoardTracker.Event.UNSTABLE, fast.onFrame(result(moved), legalMove = true))
        assertEquals(BoardTracker.Event.NEW_BOARD, fast.onFrame(result(moved), legalMove = true))

        val slow = BoardTracker(confirmCount = 3)
        repeat(3) { slow.onFrame(result(start)) }
        assertEquals(BoardTracker.Event.UNSTABLE, slow.onFrame(result(moved)))
        assertEquals(BoardTracker.Event.UNSTABLE, slow.onFrame(result(moved)))
        assertEquals(BoardTracker.Event.NEW_BOARD, slow.onFrame(result(moved)))
    }

    @Test
    fun `class-only change window stays long but bounded`() {
        // 类别纯变化仍然比普通门严，但必须有上界：真机上一帧推理约 0.3–0.7 秒，
        // 原值 8 帧意味着一张可疑画面要卡 4 秒以上，属于纯延迟而非安全性。
        assertTrue(
            PieceIdentityPolicy.CLASS_CHANGE_CONFIRM_FRAMES > BoardTracker.FAST_CONFIRM_FRAMES
        )
        assertTrue(PieceIdentityPolicy.CLASS_CHANGE_CONFIRM_FRAMES <= 4)
    }

    @Test
    fun `destination misread is repaired only when the rules accept the move`() {
        // 终点认错字：起点原类别可还原、且还原后是一步合法着法 → 采纳修复。
        val start = AssistBoard.canonicalStart()
        val misread = AssistBoard.clone(start)
        misread[7][7] = Piece.EMPTY
        misread[7][4] = Piece.WBING
        val repaired = PieceIdentityPolicy.repairMovedPiece(start, misread)
        assertNotNull(repaired)
        assertTrue(AssistBoard.isLegalSingleMove(start, repaired!!))

        // 找不到唯一空起点/唯一非空终点（例如整片抖动）：绝不猜测，返回 null。
        val noisy = AssistBoard.clone(start)
        noisy[9][0] = Piece.EMPTY
        noisy[9][8] = Piece.EMPTY
        noisy[8][0] = Piece.WBING
        noisy[8][8] = Piece.WBING
        assertEquals(null, PieceIdentityPolicy.repairMovedPiece(start, noisy))
    }

    private fun result(board: Array<IntArray>) = RecognitionResult(
        canonical = board,
        screenRaw = board,
        orientation = Orientation.STANDARD,
        issues = emptyList(),
        unknownCells = 0,
        recognizedPieces = board.sumOf { row -> row.count { it != Piece.EMPTY } },
        avgScore = 0.9,
    )
}

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

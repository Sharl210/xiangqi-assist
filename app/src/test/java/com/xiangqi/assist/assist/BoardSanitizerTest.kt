package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别结果自动修正。
 *
 * 用户反馈"校验失败，提示多出来几个马或几个炮"。根因：检测器偶发"多认"，
 * 而上一版对此的处理是**把整帧判为不合法直接丢弃**，于是连续误检就一直识别不出来。
 * 现在改成剔除多余棋子（按置信度从低到高），保住这一帧。
 */
class BoardSanitizerTest {

    private fun empty() = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
    private fun scores(cells: Map<Pair<Int, Int>, Double>): Array<DoubleArray> {
        val s = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        for ((k, v) in cells) s[k.second][k.first] = v
        return s
    }

    @Test
    fun `legal board is left untouched`() {
        val b = AssistBoard.piecesFromFen(
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1")
        val r = BoardSanitizer.sanitize(b)
        assertFalse("合法局面不该被改动", r.changed)
        assertEquals(0, AssistBoard.diffCount(b, r.board))
    }

    @Test
    fun `extra horse is dropped`() {
        // 用户报的典型症状：多出来一个马
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        b[5][1] = Piece.WMA
        b[5][7] = Piece.WMA
        b[3][3] = Piece.WMA   // 第三个马 → 非法
        val r = BoardSanitizer.sanitize(b)
        assertTrue(r.changed)
        val horses = r.board.sumOf { row -> row.count { it == Piece.WMA } }
        assertEquals(2, horses)
        assertTrue(AssistBoard.validate(r.board).isEmpty())
    }

    @Test
    fun `lowest confidence extra piece is the one removed`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        // 两个炮：一个高置信 0.95，一个低置信 0.40，再加第三个（中）0.70
        b[7][1] = Piece.WPAO
        b[7][7] = Piece.WPAO
        b[6][4] = Piece.WPAO
        val sc = scores(mapOf(
            (1 to 7) to 0.95, (7 to 7) to 0.40, (4 to 6) to 0.70
        ))
        val r = BoardSanitizer.sanitize(b, sc)
        // 最不可信的那个（0.40，位于 7,7）应当被剔除
        assertEquals(Piece.EMPTY, r.board[7][7])
        assertEquals(Piece.WPAO, r.board[7][1])
        assertEquals(Piece.WPAO, r.board[6][4])
    }

    @Test
    fun `extra soldiers are trimmed to five`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        for (x in 0 until 7) b[4][x] = Piece.WBING
        val r = BoardSanitizer.sanitize(b)
        assertEquals(5, r.board.sumOf { row -> row.count { it == Piece.WBING } })
    }

    @Test
    fun `pawn on an impossible row is dropped`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        b[8][0] = Piece.WBING   // 红兵不可能在 y8
        b[1][0] = Piece.BZU     // 黑卒不可能在 y1
        val r = BoardSanitizer.sanitize(b)
        assertEquals(Piece.EMPTY, r.board[8][0])
        assertEquals(Piece.EMPTY, r.board[1][0])
        assertEquals(2, r.notes.size)
    }

    @Test
    fun `flipped screen keeps legal red and black pawn rows`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        // canonical red pawn y6 / black pawn y3 become screen y3 / y6 after 180° flip
        b[3][0] = Piece.WBING
        b[6][8] = Piece.BZU
        val r = BoardSanitizer.sanitize(b, orientation = Orientation.FLIPPED)
        assertEquals(Piece.WBING, r.board[3][0])
        assertEquals(Piece.BZU, r.board[6][8])
        assertTrue(r.notes.isEmpty())
    }
    @Test
    fun `duplicate kings collapse to the most confident one`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[1][4] = Piece.BJIANG   // 多认了一个将
        b[9][4] = Piece.WSHUAI
        val sc = scores(mapOf((4 to 0) to 0.99, (4 to 1) to 0.30))
        val r = BoardSanitizer.sanitize(b, sc)
        assertEquals(1, r.board.sumOf { row -> row.count { it == Piece.BJIANG } })
        assertEquals(Piece.BJIANG, r.board[0][4])
        assertEquals(Piece.EMPTY, r.board[1][4])
    }


    @Test
    fun `sanitized board passes the engine safety check`() {
        // 修正之后应当能直接通过"发给引擎前"的自检（数量层面）
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        b[5][1] = Piece.WMA
        b[5][7] = Piece.WMA
        b[3][3] = Piece.WMA
        b[7][1] = Piece.WPAO
        b[7][7] = Piece.WPAO
        b[6][4] = Piece.WPAO
        val r = BoardSanitizer.sanitize(b)
        assertTrue("修正后不应再有数量超限", AssistBoard.validate(r.board).isEmpty())
        assertNotNull(r.notes)
    }

    @Test
    fun `repairs are described in human readable text`() {
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        b[3][3] = Piece.WMA
        b[3][6] = Piece.WMA
        b[5][1] = Piece.WMA
        val r = BoardSanitizer.sanitize(b)
        assertTrue(r.notes.isNotEmpty())
        assertTrue("说明要能直接给用户看：${r.notes}", r.notes.first().contains("马"))
    }

    @Test
    fun `missing pieces are never invented`() {
        // 只修正"多认"，不补子——凭空造子会带来更坏的错误
        val b = empty()
        b[0][4] = Piece.BJIANG
        b[9][4] = Piece.WSHUAI
        val r = BoardSanitizer.sanitize(b)
        assertEquals(2, r.board.sumOf { row -> row.count { it != Piece.EMPTY } })
    }
}

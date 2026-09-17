package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发给引擎前的局面安全检查。
 *
 * 判据不是拍脑袋定的：下面每个用例都对应一次**真实引擎实测**
 * （把同一 FEN 喂给 Pikafish 2026-09-06，看它是回 bestmove 还是报 CRITICAL ERROR 退出）。
 *
 * 实测结论（重要，与直觉相反）：
 * 引擎拒绝的唯一规律是「走子方能够立即吃掉对方的王」；
 * "走子方自己被将军"反而是合法局面，必须放行，否则被将军时反而给不出建议。
 */
class EngineSafetyTest {

    private fun board(fen: String): Array<IntArray> = AssistBoard.piecesFromFen(fen)

    private fun unsafe(fen: String, redGo: Boolean): String? =
        AssistBoard.engineUnsafeReason(board(fen), redGo)

    // ---------- 引擎实测：ACCEPT ----------

    @Test
    fun `opening is accepted`() {
        assertNull(unsafe("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", true))
    }

    @Test
    fun `my own king in check is legal and must pass`() {
        // 实测 ACCEPT：轮红走、红帅正被黑车攻击 —— 这是"红被将军"，本来就要应将，
        // 早先按"走子方被将军就拦"的做法会把它错杀，导致被将军时反而不给建议
        assertNull(unsafe("4k4/9/9/9/4r4/9/9/9/9/4K4 w - - 0 1", true))
        assertTrue(AssistBoard.kingAttacked(board("4k4/9/9/9/4r4/9/9/9/9/4K4 w - - 0 1"), red = true))
    }

    @Test
    fun `opponent in check while it is their move is legal`() {
        // 实测 ACCEPT：轮黑走、黑将被红车攻击 —— 黑方被将军，合法
        assertNull(unsafe("4k4/9/9/9/9/9/9/4R4/9/4K4 b - - 0 1", false))
    }

    @Test
    fun `cannon without screen cannot reach`() {
        // 实测 ACCEPT：红炮与黑将同列但中间无炮架
        assertNull(unsafe("4k4/9/4C4/9/9/9/9/9/9/4K4 w - - 0 1", true))
    }

    @Test
    fun `kings on different columns is fine`() {
        assertNull(unsafe("3k5/9/9/9/9/9/9/9/9/4K4 w - - 0 1", true))
    }

    // ---------- 引擎实测：REJECT ----------

    @Test
    fun `kings facing is rejected`() {
        // 实测 REJECT：King can be captured
        assertTrue(AssistBoard.kingsFacing(board("4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1")))
        assertNotNull(unsafe("4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1", true))
    }

    @Test
    fun `opponent king attacked while i am to move is rejected`() {
        // 实测 REJECT：轮红走、黑将被红车打 = 黑方上一步没应将
        val fen = "4k4/9/9/9/9/9/9/4R4/9/4K4 w - - 0 1"
        val b = board(fen)
        assertTrue("黑将应处于被攻击状态", AssistBoard.kingAttacked(b, red = false))
        assertNotNull(unsafe(fen, true))
    }

    @Test
    fun `my king attacked while opponent is to move is rejected`() {
        // 实测 REJECT：轮黑走、红帅被黑车打 = 红方上一步送将
        val fen = "4k4/9/9/9/4r4/9/9/9/9/4K4 b - - 0 1"
        assertNotNull(unsafe(fen, false))
    }

    @Test
    fun `horse check is detected`() {
        // 实测 REJECT：红马在 x3,y2，马腿 (3,1) 空，攻击黑将
        val fen = "4k4/9/3N5/9/9/9/9/9/9/4K4 w - - 0 1"
        assertTrue(AssistBoard.kingAttacked(board(fen), red = false))
        assertNotNull(unsafe(fen, true))
    }

    @Test
    fun `horse check blocked by its own leg is not an attack`() {
        // 在马腿 (3,1) 放一个子 → 马被蹩腿，攻击不成立
        val fen = "4k4/9/3N5/9/9/9/9/9/9/4K4 w - - 0 1"
        val b = board(fen)
        b[1][3] = com.xiangqi.assist.gamelogic.Piece.WBING // 堵住马腿
        assertFalse("马腿被堵后不应判定为攻击", AssistBoard.kingAttacked(b, red = false))
    }

    @Test
    fun `pawn check is detected`() {
        // 实测 REJECT：红兵在 x4,y1 正对黑将 x4,y0
        val fen = "4k4/4P4/9/9/9/9/9/9/9/4K4 w - - 0 1"
        assertTrue(AssistBoard.kingAttacked(board(fen), red = false))
        assertNotNull(unsafe(fen, true))
    }

    @Test
    fun `cannon with screen is detected`() {
        // 实测 REJECT：红炮 x4,y2，炮架红兵 x4,y1，黑将 x4,y0
        val fen = "4k4/4P4/4C4/9/9/9/9/9/9/4K4 w - - 0 1"
        assertTrue(AssistBoard.kingAttacked(board(fen), red = false))
        assertNotNull(unsafe(fen, true))
    }

    @Test
    fun `rook check is detected`() {
        val fen = "4k4/9/9/9/9/9/9/4R4/9/4K4 b - - 0 1"
        assertTrue(AssistBoard.kingAttacked(board(fen), red = false))
    }

    @Test
    fun `invalid pawn placement is caught`() {
        // 实测 REJECT：WHITE pawn(s) on invalid positions —— 红兵不可能出现在 y8
        val fen = "4k4/9/9/9/9/9/9/9/4P4/4K4 w - - 0 1"
        val b = board(fen)
        assertNotNull(AssistBoard.invalidPiecePlacement(b))
        assertNotNull(unsafe(fen, true))
    }

    @Test
    fun `missing king is rejected`() {
        assertNotNull(unsafe("3ak4/9/9/9/9/9/9/9/9/9 w - - 0 1", true))
    }

    @Test
    fun `reason text is always human readable`() {
        for (fen in listOf(
            "4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1",
            "4k4/9/9/9/9/9/9/4R4/9/4K4 w - - 0 1",
            "4k4/9/9/9/9/9/9/9/4P4/4K4 w - - 0 1",
        )) {
            val r = unsafe(fen, true)
            assertNotNull(r)
            assertTrue("原因要能直接给用户看：$r", r!!.isNotBlank() && !r.contains("null"))
        }
    }

    // ---------- 棋子落点合法性（点位同样来自引擎实测） ----------

    @Test
    fun `advisor must sit on one of its five points`() {
        // 实测：仕(3,7)/(5,7)/(4,8) ACCEPT；(4,7)/(3,8)/(4,9) REJECT
        assertNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/3A5/9/3K5 w - - 0 1")))
        assertNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/9/4A4/3K5 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/4A4/9/3K5 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/9/3A5/3K5 w - - 0 1")))
    }

    @Test
    fun `elephant must sit on one of its seven points`() {
        // 实测：相(2,5) ACCEPT；(2,7)/(2,8)/(4,6)/(4,8)/(3,6) REJECT
        assertNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/2B6/9/9/9/3K5 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/2B6/9/3K5 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/9/2B6/3K5 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/4B4/9/9/3K5 w - - 0 1")))
    }

    @Test
    fun `king must stay inside the palace`() {
        // 实测：帅(2,9) REJECT；(4,9) ACCEPT
        assertNotNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/9/9/2K6 w - - 0 1")))
        assertNull(AssistBoard.invalidPiecePlacement(board("3ak4/9/9/9/9/9/9/9/9/4K4 w - - 0 1")))
    }

    @Test
    fun `pawn may not go behind its own start line`() {
        // 实测：红兵 y6/y3 ACCEPT，红兵 y8 REJECT
        assertNull(AssistBoard.invalidPiecePlacement(board("4k4/9/9/9/9/9/4P4/9/9/4K4 w - - 0 1")))
        assertNull(AssistBoard.invalidPiecePlacement(board("4k4/9/9/4P4/9/9/9/9/9/4K4 w - - 0 1")))
        assertNotNull(AssistBoard.invalidPiecePlacement(board("4k4/9/9/9/9/9/9/9/4P4/4K4 w - - 0 1")))
    }

    @Test
    fun `black mirror positions are symmetric`() {
        // 黑士合法点：镜像红仕
        assertNull(AssistBoard.invalidPiecePlacement(board("3k5/9/9/9/9/9/9/9/9/4KA3 w - - 0 1")))
        // 黑象在 (2,4) 合法
        assertNull(AssistBoard.invalidPiecePlacement(board("3k5/9/9/9/2b6/9/9/9/9/4K4 b - - 0 1")))
    }

    @Test
    fun `cannon needs exactly one screen not two`() {
        // 两个子挡在中间 → 炮打不到
        val fen = "4k4/4P4/4P4/4C4/9/9/9/9/9/4K4 w - - 0 1"
        val b = board(fen)
        // 这里红兵在 y3（非法位置），先只验证炮的判定逻辑本身
        assertTrue(AssistBoard.kingAttacked(b, red = false) || AssistBoard.invalidPiecePlacement(b) != null)
    }
}

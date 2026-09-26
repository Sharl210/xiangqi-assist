package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionRecoveryCropPolicyTest {
    private fun mapped(pieceCount: Int = 20): DetectionBoardMapper.MappedBoard {
        val grid = BoardGrid.fromPxCorners(200.0, 500.0, 920.0, 1310.0, 1200, 1800)
        val board = Array(10) { IntArray(9) }
        board[9][4] = Piece.WSHUAI
        board[0][4] = Piece.BJIANG
        return DetectionBoardMapper.MappedBoard(
            screenRaw = board,
            canonical = board,
            orientation = Orientation.STANDARD,
            grid = grid,
            pieceCount = pieceCount,
            avgScore = 0.8,
            dropped = 0,
            anchorSource = DetectionBoardMapper.AnchorSource.PIECE_BBOX,
        )
    }

    @Test
    fun `piece bbox grid yields a bounded crop and matching grid anchor`() {
        val result = DetectionRecoveryCropPolicy.fromPieceBoundingGrid(mapped(), 1200, 1800)
        assertNotNull(result)
        result!!
        assertEquals(65.0, result.rect[0], 1e-6)
        assertEquals(365.0, result.rect[1], 1e-6)
        assertEquals(1055.0, result.rect[2], 1e-6)
        assertEquals(1445.0, result.rect[3], 1e-6)
        assertEquals(200.0, result.anchor.x0, 1e-6)
        assertEquals(1310.0, result.anchor.y1, 1e-6)
    }

    @Test
    fun `sparse or non bbox mappings do not trigger a second crop`() {
        assertNull(DetectionRecoveryCropPolicy.fromPieceBoundingGrid(mapped(pieceCount = 11), 1200, 1800))
        val base = mapped()
        val anchored = DetectionBoardMapper.MappedBoard(
            base.screenRaw, base.canonical, base.orientation, base.grid, base.pieceCount,
            base.avgScore, base.dropped, DetectionBoardMapper.AnchorSource.BOARD_BOX,
        )
        assertNull(DetectionRecoveryCropPolicy.fromPieceBoundingGrid(anchored, 1200, 1800))
    }
}

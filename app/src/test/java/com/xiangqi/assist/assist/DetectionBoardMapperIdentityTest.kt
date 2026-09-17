package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DetectionBoardMapperIdentityTest {
    @Test
    fun `current model class is not replaced by previous same-cell identity`() {
        val old = AssistBoard.canonicalStart()
        val boardX0 = 100.0
        val boardY0 = 200.0
        val cellW = 90.0
        val cellH = 80.0
        val x = boardX0
        val y = boardY0 + cellH * 9
        val wrongLabel = YoloDetection.PIECE_CODES.indexOf(Piece.WPAO)
        val oldLabel = YoloDetection.PIECE_CODES.indexOf(Piece.WJU)
        val dets = mutableListOf<YoloDetection>()
        for (gy in 0 until AssistBoard.H) for (gx in 0 until AssistBoard.W) {
            val p = old[gy][gx]
            if (p == Piece.EMPTY || (gx == 0 && gy == 9)) continue
            val label = YoloDetection.PIECE_CODES.indexOf(p)
            dets += YoloDetection(label, 0.9, boardX0 + gx * cellW, boardY0 + gy * cellH, 66.0, 66.0)
        }
        dets += YoloDetection(
            wrongLabel, 0.99, x, y, 66.0, 66.0,
            alternatives = listOf(wrongLabel to 0.99, oldLabel to 0.70),
        )
        dets += YoloDetection(
            YoloDetection.LABEL_BOARD, 0.95,
            boardX0 + 4 * cellW, boardY0 + 4.5 * cellH, 8 * cellW, 9 * cellH,
        )
        val mapped = DetectionBoardMapper.map(dets, 1080, 2400)
        assertNotNull(mapped)
        assertEquals(Piece.WPAO, mapped!!.screenRaw[9][0])
        assertEquals(Piece.WPAO, mapped.canonical[9][0])
    }
}

package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同格冲突可容忍性回归测试。
 *
 * 覆盖真机日志中的目标场景：同一格同时给出红车 0.708 与红炮 0.684，
 * 映射阶段已让高分候选占位，旧逻辑仍然整帧否决，导致无限重试。
 */
class DetectionConflictTolerancePolicyTest {

    private fun boardWith(row: Int, col: Int, piece: Int): Array<IntArray> =
        Array(10) { r -> IntArray(9) { c -> if (r == row && c == col) piece else Piece.EMPTY } }

    /** 真机日志场景：分差 0.024，最终棋面落在胜出的红车上，应当容忍。 */
    @Test fun `decisive small-margin conflict is tolerated when winner already occupies cell`() {
        val screen = boardWith(9, 8, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.708, secondScore = 0.684,
        )
        val verdict = DetectionConflictTolerancePolicy.evaluate(conflict, screen)
        assertTrue(verdict.tolerable)
        assertEquals(Piece.WJU, verdict.winnerPiece)
        assertEquals(0.024, verdict.gap, 1e-9)
    }

    /** 候选顺序颠倒时仍按分数判定胜者，不改写类别。 */
    @Test fun `winner is chosen by score not by argument order`() {
        val screen = boardWith(9, 8, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WPAO, secondPiece = Piece.WJU,
            firstScore = 0.684, secondScore = 0.708,
        )
        val verdict = DetectionConflictTolerancePolicy.evaluate(conflict, screen)
        assertTrue(verdict.tolerable)
        assertEquals(Piece.WJU, verdict.winnerPiece)
    }

    /** 分差低于判定门槛：属于模糊僵局，必须继续拒绝。 */
    @Test fun `ambiguous near-tie is never tolerated`() {
        val screen = boardWith(9, 8, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.700, secondScore = 0.690,
        )
        assertFalse(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }

    /** 最终棋面被写成了落败候选：冲突改变了结果，不能容忍。 */
    @Test fun `conflict that changed the final cell is rejected`() {
        val screen = boardWith(9, 8, Piece.WPAO)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.708, secondScore = 0.684,
        )
        assertFalse(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }

    /** 最终该格为空：说明冲突候选都没能占位，属于不确定状态。 */
    @Test fun `empty final cell is rejected`() {
        val screen = boardWith(9, 8, Piece.EMPTY)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.708, secondScore = 0.684,
        )
        assertFalse(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }

    /** 置信度过低的“胜利”没有意义，仍然拒绝。 */
    @Test fun `low-confidence winner is rejected`() {
        val screen = boardWith(9, 8, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 9, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.42, secondScore = 0.30,
        )
        assertFalse(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }

    /** 恰好达到最小分差时按“明确胜负”处理。 */
    @Test fun `exactly at the decisive gap is tolerated`() {
        val screen = boardWith(0, 0, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 0, col = 0,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.720, secondScore = 0.720 - DetectionConflictTolerancePolicy.MIN_DECISIVE_GAP,
        )
        assertTrue(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }

    /** 越界坐标不能读到别的格子，必须拒绝。 */
    @Test fun `out-of-range coordinates are rejected`() {
        val screen = boardWith(9, 8, Piece.WJU)
        val conflict = DetectionBoardMapper.CellClassConflict(
            row = 12, col = 8,
            firstPiece = Piece.WJU, secondPiece = Piece.WPAO,
            firstScore = 0.708, secondScore = 0.684,
        )
        assertFalse(DetectionConflictTolerancePolicy.evaluate(conflict, screen).tolerable)
    }
}

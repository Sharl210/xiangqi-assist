package com.xiangqi.assist.assist

/**
 * 判断一帧检测结果是否需要进入一次定向放大/低阈值复核。
 * 这里只决定“是否复核”，不接受结果、不放宽最终棋面安全门。
 */
object DetectionRecoveryPolicy {
    fun needsTargetedRecovery(mapped: DetectionBoardMapper.MappedBoard?): Boolean {
        if (mapped == null || mapped.cellClassConflicts.isNotEmpty()) return true
        if (!hasBothKings(mapped.canonical)) return true
        if (AssistBoard.validate(mapped.canonical).isNotEmpty()) return true
        if (AssistBoard.invalidPiecePlacement(mapped.canonical) != null) return true
        return false
    }

    private fun hasBothKings(board: Array<IntArray>): Boolean {
        var red = 0
        var black = 0
        for (row in board) for (piece in row) {
            if (piece == com.xiangqi.assist.gamelogic.Piece.WSHUAI) red++
            if (piece == com.xiangqi.assist.gamelogic.Piece.BJIANG) black++
        }
        return red == 1 && black == 1
    }
}

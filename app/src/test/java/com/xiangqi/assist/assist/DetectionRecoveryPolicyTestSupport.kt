package com.xiangqi.assist.assist

/** 测试用的 MappedBoard 复制构造，避免在生产模型中加入测试辅助 API。 */
fun DetectionBoardMapper.MappedBoard.copyForTest(
    screenRaw: Array<IntArray> = this.screenRaw,
    canonical: Array<IntArray> = this.canonical,
    pieceCount: Int = this.pieceCount,
): DetectionBoardMapper.MappedBoard = DetectionBoardMapper.MappedBoard(
    screenRaw = screenRaw,
    canonical = canonical,
    orientation = orientation,
    grid = grid,
    pieceCount = pieceCount,
    avgScore = avgScore,
    dropped = dropped,
    anchorSource = anchorSource,
    repairNotes = repairNotes,
    cellClassConflicts = cellClassConflicts,
)

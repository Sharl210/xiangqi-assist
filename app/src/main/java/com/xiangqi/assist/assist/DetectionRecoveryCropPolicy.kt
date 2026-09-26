package com.xiangqi.assist.assist

/**
 * 当 V5 漏掉棋盘类别、但棋子检测足以形成可靠格网时，生成一次放大复核区域。
 * 这只是候选裁剪与几何锚点；二次结果仍须通过既有双王、同格冲突和棋面合法性门。
 */
object DetectionRecoveryCropPolicy {
    data class Crop(val rect: DoubleArray, val anchor: DetectionBoardMapper.AnchorHint)

    fun fromPieceBoundingGrid(
        mapped: DetectionBoardMapper.MappedBoard?,
        frameW: Int,
        frameH: Int,
        minPieces: Int = 12,
        marginCells: Double = 1.5,
    ): Crop? {
        if (mapped == null || mapped.anchorSource != DetectionBoardMapper.AnchorSource.PIECE_BBOX ||
            mapped.pieceCount < minPieces || frameW <= 0 || frameH <= 0 || marginCells <= 0.0
        ) return null
        val grid = mapped.grid ?: return null
        val x0 = grid.nx0 * frameW
        val y0 = grid.ny0 * frameH
        val x1 = grid.nx1 * frameW
        val y1 = grid.ny1 * frameH
        val cellW = (x1 - x0) / 8.0
        val cellH = (y1 - y0) / 9.0
        val ratio = (x1 - x0) / (y1 - y0)
        if (!cellW.isFinite() || !cellH.isFinite() || cellW <= 0.0 || cellH <= 0.0 ||
            ratio !in 0.70..1.30
        ) return null

        val crop = doubleArrayOf(
            (x0 - cellW * marginCells).coerceAtLeast(0.0),
            (y0 - cellH * marginCells).coerceAtLeast(0.0),
            (x1 + cellW * marginCells).coerceAtMost(frameW.toDouble()),
            (y1 + cellH * marginCells).coerceAtMost(frameH.toDouble()),
        )
        if (crop[2] - crop[0] < 64.0 || crop[3] - crop[1] < 64.0) return null
        return Crop(
            rect = crop,
            anchor = DetectionBoardMapper.AnchorHint(x0, y0, x1, y1),
        )
    }
}

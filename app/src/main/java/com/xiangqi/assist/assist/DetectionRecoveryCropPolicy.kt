package com.xiangqi.assist.assist

/**
 * 当 V5 漏掉棋盘类别、但棋子检测足以形成可靠格网时，生成一次放大复核区域。
 * 裁剪只提高像素分辨率；二次结果仍须通过双王、冲突和完整棋面安全门。
 */
object DetectionRecoveryCropPolicy {
    data class Crop(val rect: DoubleArray, val anchor: DetectionBoardMapper.AnchorHint)

    /**
     * 已成功从检测结果拟合出 PIECE_BBOX 棋盘格时，按拟合格网生成裁剪。
     */
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
        return Crop(crop, DetectionBoardMapper.AnchorHint(x0, y0, x1, y1))
    }

    /**
     * 当 mapper 返回 null 时，直接从本帧当前检测中心建立一次候选包围盒。
     * 该候选只用于放大复核，不能直接作为棋盘结果；调用方必须只接受完整安全门通过的二次输出。
     */
    fun fromPieceDetections(
        detections: List<YoloDetection>,
        frameW: Int,
        frameH: Int,
        minPieces: Int = 12,
        marginCells: Double = 1.5,
    ): Crop? {
        val pieces = detections.filterNot { it.isBoard }
        if (pieces.size < minPieces || frameW <= 0 || frameH <= 0 || marginCells <= 0.0) return null
        val minX = pieces.minOfOrNull { it.cx } ?: return null
        val maxX = pieces.maxOfOrNull { it.cx } ?: return null
        val minY = pieces.minOfOrNull { it.cy } ?: return null
        val maxY = pieces.maxOfOrNull { it.cy } ?: return null
        val spanW = maxX - minX
        val spanH = maxY - minY
        if (!spanW.isFinite() || !spanH.isFinite() || spanW <= 0.0 || spanH <= 0.0 ||
            spanW / spanH !in 0.70..1.30
        ) return null
        val cellW = spanW / 8.0
        val cellH = spanH / 9.0
        val crop = doubleArrayOf(
            (minX - cellW * marginCells).coerceAtLeast(0.0),
            (minY - cellH * marginCells).coerceAtLeast(0.0),
            (maxX + cellW * marginCells).coerceAtMost(frameW.toDouble()),
            (maxY + cellH * marginCells).coerceAtMost(frameH.toDouble()),
        )
        if (crop[2] - crop[0] < 64.0 || crop[3] - crop[1] < 64.0) return null
        return Crop(crop, DetectionBoardMapper.AnchorHint(minX, minY, maxX, maxY))
    }
}

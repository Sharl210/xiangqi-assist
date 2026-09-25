package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/** 己方执子方来源：手动记忆或从屏幕下半区帅/将识别自动推断。 */
enum class SideSelectionMode {
    MANUAL,
    AUTO;

    companion object {
        fun fromStored(value: String?): SideSelectionMode = when (value) {
            AUTO.name -> AUTO
            else -> MANUAL
        }
    }
}

object SideSelectionPresentationPolicy {
    /** Only refresh an already attached, expanded panel; never create an overlay while changing mode. */
    fun shouldRefreshExpandedPanel(
        panelAttached: Boolean,
        collapsed: Boolean,
        temporarilySuppressed: Boolean,
    ): Boolean = panelAttached && !collapsed && !temporarilySuppressed
}

object SideSelectionPolicy {
    /**
     * 仅以屏幕下半区的帅/将作为自动执子方锚点：红帅在下=红方，黑将在下=黑方。
     * 缺王、重复王或半场无法唯一归属时返回 null，让调用方走异常棋面路径；不猜测。
     * 其他棋子的暂时漏检不会影响此判定。
     */
    fun detectAutoSideRed(screenRaw: Array<IntArray>): Boolean? {
        if (screenRaw.size != AssistBoard.H || screenRaw.any { it.size != AssistBoard.W }) return null

        var redKings = 0
        var blackKings = 0
        var redKingsOnBottom = 0
        var blackKingsOnBottom = 0
        for (y in 0 until AssistBoard.H) {
            for (x in 0 until AssistBoard.W) {
                when (screenRaw[y][x]) {
                    Piece.WSHUAI -> {
                        redKings++
                        if (y >= AssistBoard.H / 2) redKingsOnBottom++
                    }
                    Piece.BJIANG -> {
                        blackKings++
                        if (y >= AssistBoard.H / 2) blackKingsOnBottom++
                    }
                }
            }
        }
        if (redKings != 1 || blackKings != 1) return null
        return when {
            redKingsOnBottom == 1 && blackKingsOnBottom == 0 -> true
            blackKingsOnBottom == 1 && redKingsOnBottom == 0 -> false
            else -> null
        }
    }

    fun effectiveSideRed(
        mode: SideSelectionMode,
        manualRed: Boolean,
        autoRed: Boolean,
    ): Boolean = if (mode == SideSelectionMode.AUTO) autoRed else manualRed

    fun modeLabel(mode: SideSelectionMode): String = when (mode) {
        SideSelectionMode.MANUAL -> "手动执子方"
        SideSelectionMode.AUTO -> "自动检测执子方"
    }
}

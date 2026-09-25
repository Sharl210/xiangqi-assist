package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideSelectionPolicyTest {
    private fun flipped(screenRaw: Array<IntArray>): Array<IntArray> =
        Array(AssistBoard.H) { y -> IntArray(AssistBoard.W) { x ->
            screenRaw[AssistBoard.H - 1 - y][AssistBoard.W - 1 - x]
        } }

    @Test
    fun `manual mode always uses manual remembered side`() {
        assertTrue(SideSelectionPolicy.effectiveSideRed(SideSelectionMode.MANUAL, true, false))
        assertFalse(SideSelectionPolicy.effectiveSideRed(SideSelectionMode.MANUAL, false, true))
    }

    @Test
    fun `auto mode uses the separately remembered auto side`() {
        assertTrue(SideSelectionPolicy.effectiveSideRed(SideSelectionMode.AUTO, false, true))
        assertFalse(SideSelectionPolicy.effectiveSideRed(SideSelectionMode.AUTO, true, false))
    }

    @Test
    fun `screen bottom half king color determines side for both board orientations`() {
        val standard = AssistBoard.canonicalStart()
        assertEquals(true, SideSelectionPolicy.detectAutoSideRed(standard))
        assertEquals(false, SideSelectionPolicy.detectAutoSideRed(flipped(standard)))
        assertEquals("手动执子方", SideSelectionPolicy.modeLabel(SideSelectionMode.MANUAL))
        assertEquals("自动检测执子方", SideSelectionPolicy.modeLabel(SideSelectionMode.AUTO))
    }

    @Test
    fun `missing ordinary pieces do not affect the single king anchor`() {
        val noisy = AssistBoard.canonicalStart()
        noisy[9][0] = Piece.EMPTY
        noisy[9][1] = Piece.EMPTY
        noisy[6][0] = Piece.EMPTY
        noisy[3][8] = Piece.EMPTY
        assertEquals(true, SideSelectionPolicy.detectAutoSideRed(noisy))
        assertEquals(false, SideSelectionPolicy.detectAutoSideRed(flipped(noisy)))
    }

    @Test
    fun `missing or duplicate king is not guessed as a side`() {
        val missingRed = AssistBoard.canonicalStart().also { it[9][4] = Piece.EMPTY }
        val missingBlack = AssistBoard.canonicalStart().also { it[0][4] = Piece.EMPTY }
        val duplicateRed = AssistBoard.canonicalStart().also { it[8][4] = Piece.WSHUAI }
        val duplicateBlack = AssistBoard.canonicalStart().also { it[1][4] = Piece.BJIANG }
        assertNull(SideSelectionPolicy.detectAutoSideRed(missingRed))
        assertNull(SideSelectionPolicy.detectAutoSideRed(missingBlack))
        assertNull(SideSelectionPolicy.detectAutoSideRed(duplicateRed))
        assertNull(SideSelectionPolicy.detectAutoSideRed(duplicateBlack))
    }

    @Test
    fun `ambiguous king half placement and malformed board dimensions are rejected`() {
        val bothBottom = AssistBoard.canonicalStart().also { board ->
            board[0][4] = Piece.EMPTY
            board[9][3] = Piece.BJIANG
        }
        val neitherBottom = AssistBoard.canonicalStart().also { board ->
            board[9][4] = Piece.EMPTY
            board[0][4] = Piece.WSHUAI
        }
        assertNull(SideSelectionPolicy.detectAutoSideRed(bothBottom))
        assertNull(SideSelectionPolicy.detectAutoSideRed(neitherBottom))
        assertNull(SideSelectionPolicy.detectAutoSideRed(emptyArray()))
    }
}

class SideSelectionPresentationPolicyTest {
    @Test fun `only an attached expanded visible panel is refreshed immediately`() {
        assertTrue(SideSelectionPresentationPolicy.shouldRefreshExpandedPanel(
            panelAttached = true, collapsed = false, temporarilySuppressed = false,
        ))
        assertFalse(SideSelectionPresentationPolicy.shouldRefreshExpandedPanel(
            panelAttached = false, collapsed = false, temporarilySuppressed = false,
        ))
        assertFalse(SideSelectionPresentationPolicy.shouldRefreshExpandedPanel(
            panelAttached = true, collapsed = true, temporarilySuppressed = false,
        ))
        assertFalse(SideSelectionPresentationPolicy.shouldRefreshExpandedPanel(
            panelAttached = true, collapsed = false, temporarilySuppressed = true,
        ))
    }
}

package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextVariationPolicyTest {
    @Test fun `second tap cancels the stored variation intent`() {
        assertTrue(NextVariationPolicy.toggleArmed(false))
        assertFalse(NextVariationPolicy.toggleArmed(true))
    }

    @Test fun `single configured candidate expands only while armed on our turn`() {
        assertEquals(
            2,
            NextVariationPolicy.effectiveCandidateCount(
                configuredCount = 1,
                variationArmed = true,
                myTurn = true,
            )
        )
        assertEquals(
            1,
            NextVariationPolicy.effectiveCandidateCount(
                configuredCount = 1,
                variationArmed = true,
                myTurn = false,
            )
        )
        assertEquals(
            1,
            NextVariationPolicy.effectiveCandidateCount(
                configuredCount = 1,
                variationArmed = false,
                myTurn = true,
            )
        )
    }

    @Test fun `temporary budget applies only to armed single-candidate own turn`() {
        assertTrue(NextVariationPolicy.usesTemporaryBudget(1, variationArmed = true, myTurn = true))
        assertFalse(NextVariationPolicy.usesTemporaryBudget(1, variationArmed = false, myTurn = true))
        assertFalse(NextVariationPolicy.usesTemporaryBudget(1, variationArmed = true, myTurn = false))
        assertFalse(NextVariationPolicy.usesTemporaryBudget(2, variationArmed = true, myTurn = true))
    }

    @Test fun `temporary candidate is requested only when the second line is absent`() {
        assertTrue(NextVariationPolicy.needsTemporaryCandidate(1, true, true, 0))
        assertTrue(NextVariationPolicy.needsTemporaryCandidate(1, true, true, 1))
        assertFalse(NextVariationPolicy.needsTemporaryCandidate(1, true, true, 2))
        assertFalse(NextVariationPolicy.needsTemporaryCandidate(2, true, true, 1))
        assertFalse(NextVariationPolicy.needsTemporaryCandidate(1, true, false, 1))
    }

    @Test fun `no alternative remains pending until new candidate arrives`() {
        val request = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1"), null)
        assertNull(request.preferredUcci)
        assertTrue(NextVariationPolicy.resolve(request, "fen", listOf("a0a1")).waitingForAlternative)
        val later = NextVariationPolicy.resolve(request, "fen", listOf("a0a1", "b0b1"))
        assertEquals("b0b1", later.ucci)
        assertEquals(1, later.candidateIndex)
        assertFalse(later.waitingForAlternative)
    }

    @Test fun `PV reordering preserves the requested move by UCCI`() {
        val request = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1", "c0c1"), null)
        val result = NextVariationPolicy.resolve(request, "fen", listOf("a0a1", "c0c1", "b0b1"))
        assertEquals("b0b1", result.ucci)
        assertEquals(2, result.candidateIndex)
    }

    @Test fun `a repeated tap advances without wrapping into the default move`() {
        val first = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1", "c0c1"), null)
        val second = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1", "c0c1"), first)
        assertEquals("c0c1", second.preferredUcci)
        val third = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1", "c0c1"), second)
        assertEquals("b0b1", third.preferredUcci)
    }

    @Test fun `temporary candidate disappearance keeps the original request pending`() {
        val request = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1", "c0c1"), null)
        val missing = NextVariationPolicy.resolve(request, "fen", listOf("a0a1", "c0c1"))
        assertTrue(missing.waitingForAlternative)
        assertNull(missing.ucci)
        val returned = NextVariationPolicy.resolve(request, "fen", listOf("a0a1", "b0b1", "c0c1"))
        assertEquals("b0b1", returned.ucci)
    }

    @Test fun `position change invalidates prior request`() {
        val request = NextVariationPolicy.request("fen-a", "a0a1", listOf("a0a1", "b0b1"), null)
        val result = NextVariationPolicy.resolve(request, "fen-b", listOf("d0d1", "e0e1"))
        assertTrue(result.invalidated)
        assertNull(result.request)
    }

    @Test fun `request remains until the requested move is dispatched`() {
        val request = NextVariationPolicy.request("fen", "a0a1", listOf("a0a1", "b0b1"), null)
        assertEquals(request, NextVariationPolicy.consume(request, "fen", "a0a1"))
        assertNull(NextVariationPolicy.consume(request, "fen", "b0b1"))
    }

    @Test fun `request can be created without a current position candidate`() {
        val request = NextVariationPolicy.request("fen", null, emptyList(), null)
        assertEquals("fen", request.position)
        assertNull(request.preferredUcci)
        assertTrue(NextVariationPolicy.resolve(request, "fen", emptyList()).waitingForAlternative)
        val later = NextVariationPolicy.resolve(request, "fen", listOf("a0a1", "b0b1"))
        assertEquals("b0b1", later.ucci)
    }

    @Test fun `prearmed request uses actual current default when it appears later`() {
        val armed = NextVariationPolicy.request("fen", null, emptyList(), null)
        val later = NextVariationPolicy.resolve(
            request = armed,
            position = "fen",
            candidates = listOf("b0b1", "a0a1"),
            currentDefaultUcci = "a0a1",
        )
        assertEquals("b0b1", later.ucci)
        assertEquals("a0a1", later.request?.defaultUcci)
    }

    @Test fun `armed request stays pending while the position is not yet the side to move`() {
        val request = NextVariationPolicy.request("future-fen", null, emptyList(), null)
        assertTrue(NextVariationPolicy.resolve(request, "future-fen", emptyList()).waitingForAlternative)
    }
}

class OverlayPresentationPolicyTest {
    @Test fun `notification content follows collapsed expanded and temporary presentation`() {
        val base = "正在识别局面"
        assertEquals(base, OverlayPresentationPolicy.notificationContent(OverlayPresentationPolicy.Mode.PANEL, base))
        assertTrue(OverlayPresentationPolicy.notificationContent(OverlayPresentationPolicy.Mode.BALL, base).contains("悬浮球已显示"))
        assertTrue(OverlayPresentationPolicy.notificationContent(OverlayPresentationPolicy.Mode.TEMPORARY_BALL_FOR_MOVE, base).contains("临时显示"))
        assertFalse(OverlayPresentationPolicy.isCollapsed(OverlayPresentationPolicy.Mode.PANEL))
        assertTrue(OverlayPresentationPolicy.isCollapsed(OverlayPresentationPolicy.Mode.BALL))
    }
}

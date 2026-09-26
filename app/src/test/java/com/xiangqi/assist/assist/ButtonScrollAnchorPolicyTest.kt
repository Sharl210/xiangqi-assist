package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonScrollAnchorPolicyTest {
    @Test
    fun `anchor button moves to its new line when panel row count changes`() {
        val anchor = ButtonScrollAnchorPolicy.Anchor(buttonIndex = 10, offsetWithinButtonPx = -14)
        assertEquals(0, ButtonScrollAnchorPolicy.rowContainingAnchor(anchor, itemCount = 14, rowCount = 1))
        assertEquals(2, ButtonScrollAnchorPolicy.rowContainingAnchor(anchor, itemCount = 14, rowCount = 3))
        assertEquals(1, ButtonScrollAnchorPolicy.rowContainingAnchor(anchor, itemCount = 14, rowCount = 2))
    }

    @Test
    fun `invalid or removed anchor does not select a row`() {
        assertNull(ButtonScrollAnchorPolicy.rowContainingAnchor(null, itemCount = 12, rowCount = 2))
        assertNull(
            ButtonScrollAnchorPolicy.rowContainingAnchor(
                ButtonScrollAnchorPolicy.Anchor(buttonIndex = 12, offsetWithinButtonPx = 0),
                itemCount = 12,
                rowCount = 3,
            )
        )
    }

    @Test
    fun `stale row child index is rejected before delayed view access`() {
        val anchor = ButtonScrollAnchorPolicy.Anchor(buttonIndex = 5, offsetWithinButtonPx = 0)
        assertEquals(
            1,
            ButtonScrollAnchorPolicy.childIndexForAnchor(
                anchor = anchor,
                rowStart = 4,
                rowEndExclusive = 7,
                childCount = 3,
            ),
        )
        assertNull(
            ButtonScrollAnchorPolicy.childIndexForAnchor(
                anchor = anchor,
                rowStart = 4,
                rowEndExclusive = 7,
                childCount = 1,
            ),
        )
        assertNull(
            ButtonScrollAnchorPolicy.childIndexForAnchor(
                anchor = anchor,
                rowStart = 0,
                rowEndExclusive = 4,
                childCount = 3,
            ),
        )
    }

}

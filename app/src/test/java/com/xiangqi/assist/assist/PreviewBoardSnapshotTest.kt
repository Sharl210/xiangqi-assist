package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreviewBoardSnapshotTest {
    @Test
    fun `matching board and analysis reuses the captured landing points`() {
        val snapshot = PreviewBoardSnapshot()
        snapshot.capture(
            fen = "fen",
            analysisId = 7L,
            ucci = "h2e2",
            fromX = 0.2f,
            fromY = 0.8f,
            toX = 0.4f,
            toY = 0.8f,
            now = 1_000L,
        )
        val entry = snapshot.reusableFor("fen", 7L, 2_000L, 1_500L)
        assertNotNull(entry)
        assertEquals(0.2f, entry!!.fromX, 0.0001f)
        assertEquals(0.4f, entry.toX, 0.0001f)
    }

    @Test
    fun `board session and age mismatches invalidate the snapshot`() {
        val snapshot = PreviewBoardSnapshot()
        snapshot.capture("fen", 7L, "h2e2", 0.2f, 0.8f, 0.4f, 0.8f, 1_000L)
        assertNull(snapshot.reusableFor("other", 7L, 2_000L, 1_500L))
        assertNull(snapshot.reusableFor("fen", 8L, 2_000L, 1_500L))
        assertNull(snapshot.reusableFor("fen", 7L, 100L, 1_500L))
    }

    @Test
    fun `cleared snapshot cannot be reused`() {
        val snapshot = PreviewBoardSnapshot()
        snapshot.capture("fen", 7L, "h2e2", 0.2f, 0.8f, 0.4f, 0.8f, 1_000L)
        snapshot.clear()
        assertNull(snapshot.reusableFor("fen", 7L, 2_000L, 1_500L))
    }
}

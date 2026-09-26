package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingModeTest {
    @Test
    fun `default thinking mode is total time and default total budget is three seconds`() {
        assertEquals(ThinkingMode.TOTAL_TIME, ThinkingOptions.DEFAULT_MODE)
        assertEquals(3_000, ThinkingOptions.DEFAULT_TIME_MS)
        assertEquals(
            "go movetime 3000",
            AnalysisBudget.forTotalTime(ThinkingOptions.DEFAULT_TIME_MS, candidates = 3).goCommand()
        )
    }

    @Test
    fun `time levels stop at one hundred and twenty seconds`() {
        assertEquals(
            listOf(100L, 1_000L, 3_000L, 5_000L, 8_000L, 16_000L,
                20_000L, 30_000L, 60_000L, 90_000L, 120_000L),
            ThinkingOptions.TIME_LEVELS_MS
        )
        assertFalse(ThinkingOptions.TIME_LEVELS_MS.contains(240_000L))
        assertFalse(ThinkingOptions.TIME_LEVELS_MS.any { it > 120_000L })
        assertEquals(120_000L, ThinkingOptions.TIME_LEVELS_MS.last())
        assertTrue(ThinkingOptions.TIME_LEVELS_MS.contains(ThinkingOptions.DEFAULT_TIME_MS.toLong()))
        assertEquals(120_000, ThinkingOptions.MAX_TIME_MS)
    }

    @Test
    fun `legacy time value maps to total time mode`() {
        assertEquals(ThinkingMode.TOTAL_TIME, ThinkingMode.fromStored("TIME"))
    }

    @Test
    fun `thinking modes cycle through depth total and per candidate`() {
        assertEquals(ThinkingMode.TOTAL_TIME, ThinkingMode.DEPTH.next())
        assertEquals(ThinkingMode.PER_CANDIDATE_TIME, ThinkingMode.TOTAL_TIME.next())
        assertEquals(ThinkingMode.DEPTH, ThinkingMode.PER_CANDIDATE_TIME.next())
    }

    @Test
    fun `per candidate budget multiplies once for one multipv search`() {
        val budget = AnalysisBudget.forPerCandidateTime(3_000, candidates = 3)
        assertEquals(9_000, budget.totalTimeMs)
        assertEquals(3_000, budget.perCandidateTimeMs)
        assertEquals("go movetime 9000", budget.goCommand())
    }

    @Test
    fun `time formatting covers seconds and sub second values`() {
        assertEquals("0.1s", ThinkingOptions.formatTime(100L))
        assertEquals("3s", ThinkingOptions.formatTime(3_000L))
        assertEquals("120s", ThinkingOptions.formatTime(120_000L))
    }
}

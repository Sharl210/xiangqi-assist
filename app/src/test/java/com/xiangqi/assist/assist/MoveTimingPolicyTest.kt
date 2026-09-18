package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTimingPolicyTest {
    @Test
    fun `tap gap stays between thirty and one hundred percent of base`() {
        val base = 220L
        val random = java.util.Random(42L)
        val values = (0 until 500).map {
            MoveTimingPolicy.randomizedTapGapMs(base, random)
        }
        val minimum = 66L // ceil(220 * 0.30)
        assertTrue(values.all { it in minimum..base })
        assertTrue(values.minOrNull()!! <= minimum + 2L)
        assertTrue(values.maxOrNull()!! >= base - 2L)
    }

    @Test
    fun `randomized gap varies while preserving the selected base`() {
        val random = java.util.Random(7L)
        val values = (0 until 30).map {
            MoveTimingPolicy.randomizedTapGapMs(500L, random)
        }
        assertTrue(values.distinct().size > 1)
        assertTrue(values.all { MoveTimingPolicy.factorOf(it, 500L) in 0.30..1.0 })
    }

    @Test
    fun `degenerate base still produces a valid positive gap`() {
        assertEquals(1L, MoveTimingPolicy.randomizedTapGapMs(0L, java.util.Random(1L)))
        assertEquals(1L, MoveTimingPolicy.randomizedTapGapMs(1L, java.util.Random(1L)))
    }
}

package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisCycleGateTest {
    @Test
    fun `same position reuses one cycle`() {
        val gate = AnalysisCycleGate()
        assertEquals(AnalysisCycleGate.Decision.START, gate.beginOrReuse("fen-a"))
        assertEquals(AnalysisCycleGate.Decision.REUSE, gate.beginOrReuse("fen-a"))
        assertEquals("fen-a", gate.activePosition())
    }

    @Test
    fun `new position starts a new cycle only after invalidation`() {
        val gate = AnalysisCycleGate()
        gate.beginOrReuse("fen-a")
        assertEquals(AnalysisCycleGate.Decision.START, gate.beginOrReuse("fen-b"))
        gate.invalidate()
        assertNull(gate.activePosition())
        assertEquals(AnalysisCycleGate.Decision.START, gate.beginOrReuse("fen-b"))
    }
}

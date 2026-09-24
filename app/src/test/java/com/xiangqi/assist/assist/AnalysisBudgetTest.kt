package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 同一棋面候选共享一份总预算，浏览候选不产生新搜索。 */
class AnalysisBudgetTest {
    @Test
    fun `candidate count setting is one to six and defaults to one`() {
        assertEquals(1, ThinkingOptions.DEFAULT_CANDIDATE_COUNT)
        assertEquals(1, ThinkingOptions.MIN_CANDIDATE_COUNT)
        assertEquals(6, ThinkingOptions.MAX_CANDIDATE_COUNT)
        assertEquals("go movetime 3000", AnalysisBudget.forTotalTime(3_000).goCommand())
        assertEquals(6, AnalysisBudget(null, 3_000, 6).normalized().candidateCount)
    }
    @Test
    fun `time mode emits exactly one go command with one total movetime`() {
        val budget = AnalysisBudget.forTotalTime(3_000, candidates = 3)
        assertNull(budget.maxDepth)
        assertEquals(3_000, budget.totalTimeMs)
        assertEquals(3, budget.candidateCount)
        assertEquals("go movetime 3000", budget.goCommand())
    }

    @Test
    fun `depth mode emits one depth command for all candidates`() {
        val budget = AnalysisBudget.forDepth(64, candidates = 3)
        assertEquals("go depth 64", budget.goCommand())
        assertEquals(3, budget.candidateCount)
    }

    @Test
    fun `depth mode has no hidden time cap`() {
        // 深度模式只发用户选择的深度，不偷偷追加 movetime；搜索自然跑到该深度。
        assertEquals("go depth 20", AnalysisBudget.forDepth(20, candidates = 3).goCommand())
        assertEquals("go depth 64", AnalysisBudget.forDepth(64, candidates = 3).goCommand())
    }

    @Test
    fun `candidate browsing stops at last result instead of looping`() {
        assertEquals(1, CandidatePolicy.nextDisplayed(0, 3))
        assertEquals(2, CandidatePolicy.nextDisplayed(1, 3))
        assertEquals(2, CandidatePolicy.nextDisplayed(2, 3))
        assertEquals(0, CandidatePolicy.nextDisplayed(0, 1))
    }

    @Test
    fun `best route is multipv one regardless of display order`() {
        val best = AnalysisLine(multiPv = 1, redScoreCp = 80, pv = listOf("a0a1"))
        val second = AnalysisLine(multiPv = 2, redScoreCp = 30, pv = listOf("b0c2"))
        val third = AnalysisLine(multiPv = 3, redScoreCp = 10, pv = listOf("c3c4"))
        assertSame(best, CandidatePolicy.bestLine(listOf(third, second, best)))
    }

    @Test
    fun `two hundred and forty seconds is the maximum and three hundred and sixty is clamped`() {
        assertEquals(240_000, AnalysisBudget.forTotalTime(240_000).normalized().totalTimeMs)
        assertEquals("go movetime 240000", AnalysisBudget.forTotalTime(240_000).goCommand())
        // 旧配置（历史上存过 360 秒）必须被夹到 240 秒
        val legacy = AnalysisBudget(null, 360_000)
        assertEquals(360_000, legacy.totalTimeMs)
        assertEquals("go movetime 360000", legacy.goCommand())
        assertEquals(240_000, legacy.normalized().totalTimeMs)
        assertEquals("go movetime 240000", legacy.normalized().goCommand())
    }

    @Test
    fun `status label follows the exact total time command budget`() {
        val budget = AnalysisBudget.forTotalTime(100, candidates = 1).normalized()
        assertEquals("go movetime 100", budget.goCommand())
        assertEquals("1条共享总时 0.1s", budget.statusLabel())
    }

    @Test
    fun `per candidate status label reports multiplied total budget`() {
        val one = AnalysisBudget.forPerCandidateTime(3_000, candidates = 1).normalized()
        assertEquals("1条 · 每候选 3s（总计 3s）", one.statusLabel())
        val three = AnalysisBudget.forPerCandidateTime(3_000, candidates = 3).normalized()
        assertEquals("3条 · 每候选 3s（总计 9s）", three.statusLabel())
        assertEquals("go movetime 9000", three.goCommand())
    }

    @Test
    fun `temporary variation total mode doubles the original single candidate time`() {
        val budget = AnalysisBudget.forEqualizedTotalTime(3_000, candidates = 2).normalized()
        assertEquals(2, budget.candidateCount)
        assertEquals(6_000, budget.totalTimeMs)
        assertEquals("go movetime 6000", budget.goCommand())
        assertEquals("2条 · 每候选 3s（总计 6s）", budget.statusLabel())
    }

    @Test
    fun `temporary variation supports the extended total ceiling without changing normal total mode`() {
        val temporary = AnalysisBudget.forEqualizedTotalTime(240_000, candidates = 2).normalized()
        assertEquals(480_000, temporary.totalTimeMs)
        assertEquals("go movetime 480000", temporary.goCommand())
        assertEquals(240_000, AnalysisBudget.forTotalTime(240_000, candidates = 1).normalized().totalTimeMs)
    }
    @Test
    fun `depth status label uses the submitted depth budget`() {
        val budget = AnalysisBudget.forDepth(20, candidates = 1).normalized()
        assertEquals("深度 20 · 1条共享", budget.statusLabel())
        assertEquals("go depth 20", budget.goCommand())
    }

    @Test
    fun `depth is clamped to sixty four inside a budget`() {
        assertEquals(64, AnalysisBudget(maxDepth = 512, totalTimeMs = 0).normalized().maxDepth)
    }
}

package com.xiangqi.assist.assist

import com.xiangqi.assist.assist.CandidatePreviewPolicy.FinalDecision
import com.xiangqi.assist.assist.CandidatePreviewPolicy.PreviewDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝色候选箭头的起点预选与绿色最终落子的决策规则。
 */
class CandidatePreviewPolicyTest {

    private fun preview(
        autoCanRun: Boolean = true,
        landingInProgress: Boolean = false,
        fen: String = "FEN1",
        analysisId: Long = 7L,
        ucci: String? = "h2e2",
        originPointValid: Boolean = true,
        selectedFen: String? = null,
        selectedAnalysisId: Long = -1L,
        selectedOrigin: String? = null,
        selectionGestureCompleted: Boolean = false,
    ) = CandidatePreviewPolicy.decidePreview(
        CandidatePreviewPolicy.PreviewInput(
            autoCanRun, landingInProgress, fen, analysisId, ucci, originPointValid,
            selectedFen, selectedAnalysisId, selectedOrigin, selectionGestureCompleted,
        )
    )

    private fun final(
        fen: String = "FEN1",
        analysisId: Long = 7L,
        ucci: String = "h2e2",
        selectedFen: String? = "FEN1",
        selectedAnalysisId: Long = 7L,
        selectedOrigin: String? = "h2",
        selectionGestureCompleted: Boolean = true,
    ) = CandidatePreviewPolicy.decideFinal(
        CandidatePreviewPolicy.FinalInput(
            fen, analysisId, ucci, selectedFen, selectedAnalysisId, selectedOrigin,
            selectionGestureCompleted,
        )
    )

    // ---------- 预选的门槛 ----------

    @Test
    fun `no preview when autoplay is off or the channel is down`() {
        assertEquals(PreviewDecision.NO_OP, preview(autoCanRun = false))
    }

    @Test
    fun `no preview while a landing transaction is running`() {
        assertEquals(PreviewDecision.NO_OP, preview(landingInProgress = true))
    }

    @Test
    fun `no preview without a usable candidate or a valid origin`() {
        assertEquals(PreviewDecision.NO_OP, preview(ucci = null))
        assertEquals(PreviewDecision.NO_OP, preview(ucci = "h2"))
        assertEquals(PreviewDecision.NO_OP, preview(originPointValid = false))
        assertEquals(PreviewDecision.NO_OP, preview(fen = ""))
    }

    @Test
    fun `the same candidate origin is never clicked twice`() {
        assertEquals(
            PreviewDecision.ALREADY_SELECTED,
            preview(selectedFen = "FEN1", selectedAnalysisId = 7L, selectedOrigin = "b2"),
        )
        assertEquals(
            PreviewDecision.ALREADY_SELECTED,
            preview(selectedFen = "FEN1", selectedAnalysisId = 7L, selectedOrigin = "h2"),
        )
    }

    @Test
    fun `a different tentative pv origin in the same analysis is not previewed again`() {
        assertEquals(
            PreviewDecision.ALREADY_SELECTED,
            preview(ucci = "b2c2", selectedFen = "FEN1", selectedAnalysisId = 7L, selectedOrigin = "h2"),
        )
    }
    @Test
    fun `a new board or a new analysis session clears the dedupe`() {
        // 棋面变了 → 同一 UCCI 起点也要重新预选
        assertEquals(
            PreviewDecision.DISPATCH_ORIGIN_PREVIEW,
            preview(selectedFen = "FEN0", selectedAnalysisId = 7L, selectedOrigin = "h2"),
        )
        // 新分析会话（同一棋面重算）→ 同样重新预选
        assertEquals(
            PreviewDecision.DISPATCH_ORIGIN_PREVIEW,
            preview(selectedFen = "FEN1", selectedAnalysisId = 6L, selectedOrigin = "h2"),
        )
    }

    @Test
    fun `same origin with a different destination is not clicked again`() {
        // h2e2 与 h2h6 共用起点 h2：起点已经选好，不再重复点击
        assertEquals(
            PreviewDecision.ALREADY_SELECTED,
            preview(ucci = "h2h6", selectedFen = "FEN1", selectedAnalysisId = 7L, selectedOrigin = "h2"),
        )
    }

    // ---------- 最终落子 ----------

    @Test
    fun `final move reuses a completed matching selection`() {
        assertEquals(FinalDecision.REUSE_SELECTED_ORIGIN, final())
    }

    @Test
    fun `final move falls back to the full gesture when the selection does not match`() {
        assertEquals(FinalDecision.FULL_MOVE, final(selectedOrigin = "b2"))
        assertEquals(FinalDecision.FULL_MOVE, final(selectedFen = "FEN0"))
        assertEquals(FinalDecision.FULL_MOVE, final(selectedAnalysisId = 6L))
        assertEquals(FinalDecision.FULL_MOVE, final(selectedOrigin = null))
        assertEquals(FinalDecision.FULL_MOVE, final(ucci = "zz"))
    }

    @Test
    fun `the final move waits instead of stacking gestures`() {
        assertEquals(
            FinalDecision.WAIT_SELECTION,
            final(selectionGestureCompleted = false),
        )
    }

    // ---------- 状态容器 ----------

    @Test
    fun `state covers the full preview lifecycle`() {
        val st = CandidatePreviewState()
        assertNull(st.origin)
        assertFalse(st.gestureCompleted)

        st.onContext("FEN1", 7L)
        val token = st.markDispatched("h2")
        assertEquals("h2", st.origin)
        assertFalse(st.gestureCompleted)

        st.markCompleted(token, true)
        assertTrue(st.gestureCompleted)
        assertEquals(
            FinalDecision.REUSE_SELECTED_ORIGIN,
            final(selectedFen = st.fen, selectedAnalysisId = st.analysisId, selectedOrigin = st.origin),
        )

        // 换棋面：全部清空
        st.onContext("FEN2", 8L)
        assertNull(st.origin)
        assertFalse(st.gestureCompleted)
    }

    @Test
    fun `a cancelled gesture invalidates the selection`() {
        val st = CandidatePreviewState()
        st.onContext("FEN1", 7L)
        val token = st.markDispatched("h2")
        st.markCompleted(token, false)
        assertNull(st.origin)
        assertFalse(st.gestureCompleted)
        assertEquals(FinalDecision.FULL_MOVE, final(selectedOrigin = st.origin))
    }

    @Test
    fun `a stale gesture callback cannot mark a new selection as completed`() {
        val st = CandidatePreviewState()
        st.onContext("FEN1", 7L)
        val old = st.markDispatched("h2")
        val fresh = st.markDispatched("b2")
        st.markCompleted(old, true)      // 迟到的旧回调
        assertFalse(st.gestureCompleted)
        st.markCompleted(fresh, true)
        assertTrue(st.gestureCompleted)
    }

    @Test
    fun `clearing the state removes any reusable origin`() {
        val st = CandidatePreviewState()
        st.onContext("FEN1", 7L)
        val token = st.markDispatched("h2")
        st.markCompleted(token, true)
        st.clear()
        assertNull(st.origin)
        assertFalse(st.gestureCompleted)
    }

    @Test
    fun `origin of a ucci move is its first two characters`() {
        assertEquals("h2", CandidatePreviewPolicy.originOf("h2e2"))
        assertEquals("a0", CandidatePreviewPolicy.originOf("a0a1"))
    }
}

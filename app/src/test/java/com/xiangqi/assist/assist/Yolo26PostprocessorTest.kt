package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Yolo26PostprocessorTest {
    private fun output(): Array<FloatArray> =
        Array(Yolo26Postprocessor.DIMS) { FloatArray(Yolo26Postprocessor.ANCHORS) }

    @Test
    fun `raw yolo26 output decodes board and piece with its own contract`() {
        val raw = output()
        // Board box in the 640 input domain.
        put(raw, 0, cx = 320f, cy = 320f, w = 512f, h = 576f, label = 14, score = 0.95f)
        // Red king in the board centre; raw output has no objectness column.
        put(raw, 1, cx = 320f, cy = 320f, w = 60f, h = 60f, label = 10, score = 0.91f)

        val decoded = Yolo26Postprocessor.decode(
            output = raw,
            lb = YoloPostprocessor.Letterbox.forFrame(640, 640),
            frameW = 640,
            frameH = 640,
        )

        assertEquals(2, decoded.size)
        assertEquals(1, decoded.count { it.isBoard })
        assertEquals(1, decoded.count { !it.isBoard })
        assertEquals(10, decoded.single { !it.isBoard }.labelId)
        assertEquals(320.0, decoded.single { !it.isBoard }.cx, 0.01)
        assertEquals(320.0, decoded.single { !it.isBoard }.cy, 0.01)
    }

    @Test
    fun `raw class order is normalized to legacy piece mapping`() {
        val raw = output()
        put(raw, 0, cx = 320f, cy = 320f, w = 512f, h = 576f, label = 14, score = 0.95f)
        put(raw, 1, cx = 300f, cy = 300f, w = 60f, h = 60f, label = 0, score = 0.90f)
        put(raw, 2, cx = 340f, cy = 300f, w = 60f, h = 60f, label = 6, score = 0.89f)

        val decoded = Yolo26Postprocessor.decode(
            raw,
            YoloPostprocessor.Letterbox.forFrame(640, 640),
            640,
            640,
        )

        assertEquals(setOf(2, 4), decoded.filter { !it.isBoard }.map { it.labelId }.toSet())
    }

    @Test
    fun `close class competition is rejected instead of guessed`() {
        val raw = output()
        put(raw, 0, cx = 320f, cy = 320f, w = 512f, h = 576f, label = 14, score = 0.95f)
        raw[4 + 4][1] = 0.70f
        raw[4 + 5][1] = 0.68f
        raw[0][1] = 320f
        raw[1][1] = 320f
        raw[2][1] = 60f
        raw[3][1] = 60f

        val decoded = Yolo26Postprocessor.decode(
            output = raw,
            lb = YoloPostprocessor.Letterbox.forFrame(640, 640),
            frameW = 640,
            frameH = 640,
            classMarginMin = 0.03,
        )

        assertTrue(decoded.none { !it.isBoard })
        assertEquals(1, decoded.count { it.isBoard })
    }

    @Test
    fun `different classes at the same location are both retained for cell conflict gate`() {
        val raw = output()
        put(raw, 0, cx = 320f, cy = 320f, w = 512f, h = 576f, label = 14, score = 0.95f)
        put(raw, 1, cx = 320f, cy = 320f, w = 60f, h = 60f, label = 4, score = 0.90f)
        put(raw, 2, cx = 320f, cy = 320f, w = 60f, h = 60f, label = 5, score = 0.85f)

        val decoded = Yolo26Postprocessor.decode(
            output = raw,
            lb = YoloPostprocessor.Letterbox.forFrame(640, 640),
            frameW = 640,
            frameH = 640,
        )

        assertEquals(3, decoded.size)
        assertEquals(setOf(0, 6), decoded.filter { !it.isBoard }.map { it.labelId }.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong output channel count is rejected`() {
        Yolo26Postprocessor.decode(
            output = Array(20) { FloatArray(Yolo26Postprocessor.ANCHORS) },
            lb = YoloPostprocessor.Letterbox.forFrame(640, 640),
            frameW = 640,
            frameH = 640,
        )
    }

    private fun put(
        raw: Array<FloatArray>,
        anchor: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        label: Int,
        score: Float,
    ) {
        raw[0][anchor] = cx
        raw[1][anchor] = cy
        raw[2][anchor] = w
        raw[3][anchor] = h
        raw[4 + label][anchor] = score
    }
}

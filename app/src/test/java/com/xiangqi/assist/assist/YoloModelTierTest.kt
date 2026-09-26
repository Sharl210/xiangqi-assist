package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModelTierTest {
    @Test
    fun `fallback order is super large large medium lite`() {
        assertEquals(
            listOf(
                YoloModelTier.SUPER_LARGE,
                YoloModelTier.LARGE,
                YoloModelTier.MEDIUM,
                YoloModelTier.MEDIUM_V5_FALLBACK,
                YoloModelTier.LITE,
            ),
            YoloModelTier.SUPER_LARGE.fallbackOrder(),
        )
        assertEquals(
            listOf(YoloModelTier.LARGE, YoloModelTier.MEDIUM, YoloModelTier.MEDIUM_V5_FALLBACK, YoloModelTier.LITE),
            YoloModelTier.LARGE.fallbackOrder(),
        )
        assertEquals(
            listOf(YoloModelTier.MEDIUM, YoloModelTier.MEDIUM_V5_FALLBACK, YoloModelTier.LITE),
            YoloModelTier.MEDIUM.fallbackOrder(),
        )
        assertEquals(listOf(YoloModelTier.MEDIUM_V5_FALLBACK, YoloModelTier.LITE), YoloModelTier.MEDIUM_V5_FALLBACK.fallbackOrder())
        assertEquals(listOf(YoloModelTier.LITE), YoloModelTier.LITE.fallbackOrder())
    }

    @Test
    fun `v5 medium fallback is hidden from chooser`() {
        assertFalse(YoloModelTier.MEDIUM_V5_FALLBACK.selectable)
    }

    @Test
    fun `unknown stored value uses effect first default`() {
        assertEquals(YoloModelTier.SUPER_LARGE, YoloModelTier.fromStored(null))
        assertEquals(YoloModelTier.SUPER_LARGE, YoloModelTier.fromStored("old-value"))
        assertEquals(YoloModelTier.MEDIUM, YoloModelTier.fromStored("MEDIUM"))
    }

    @Test
    fun `model budget is two hundred million bytes`() {
        assertEquals(200_000_000L, YoloModelTier.MAX_MODEL_BYTES)
    }

    @Test
    fun `fallback result exposes reason and actual tier`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.SUPER_LARGE,
            actual = YoloModelTier.LARGE,
            modelFile = YoloModelTier.LARGE.fileName,
            failureReasons = listOf("超大型：资产不存在"),
        )
        assertTrue(result.success)
        assertTrue(result.wasFallback)
        assertTrue(result.userMessage().contains("超大型模型未通过设备兼容性检查"))
        assertTrue(result.userMessage().contains("大型模型"))
    }

    @Test
    fun `large tier uses restored historical ensemble asset`() {
        assertEquals("yolov5l_xq_fp32.tflite", YoloModelTier.LARGE.fileName)
        assertTrue(YoloModelTier.LARGE.selectionHint.contains("复合"))
    }

    @Test
    fun `successful legacy lite result is not a fallback`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.LITE,
            actual = YoloModelTier.LITE,
            modelFile = YoloModelTier.LITE.fileName,
        )
        assertTrue(result.success)
        assertFalse(result.wasFallback)
        assertTrue(result.userMessage().contains("已启用Lite（V5保底）模型"))
    }
}

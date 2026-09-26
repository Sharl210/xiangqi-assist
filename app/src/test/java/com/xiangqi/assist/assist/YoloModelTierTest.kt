package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModelTierTest {
    @Test
    fun `tiers are exposed from lite to high and fallback descends by effect priority`() {
        assertEquals(
            listOf("Lite", "Low", "Medium", "High"),
            YoloModelTier.values().map { it.displayName },
        )
        assertEquals(
            listOf(YoloModelTier.HIGH, YoloModelTier.MEDIUM, YoloModelTier.LOW, YoloModelTier.LITE),
            YoloModelTier.HIGH.fallbackOrder(),
        )
        assertEquals(
            listOf(YoloModelTier.MEDIUM, YoloModelTier.LOW, YoloModelTier.LITE),
            YoloModelTier.MEDIUM.fallbackOrder(),
        )
        assertEquals(
            listOf(YoloModelTier.LOW, YoloModelTier.LITE),
            YoloModelTier.LOW.fallbackOrder(),
        )
        assertEquals(listOf(YoloModelTier.LITE), YoloModelTier.LITE.fallbackOrder())
    }

    @Test
    fun `legacy stored values migrate to current effect labels`() {
        assertEquals(YoloModelTier.HIGH, YoloModelTier.fromStored(null))
        assertEquals(YoloModelTier.HIGH, YoloModelTier.fromStored("old-value"))
        assertEquals(YoloModelTier.HIGH, YoloModelTier.fromStored("SUPER_LARGE"))
        assertEquals(YoloModelTier.HIGH, YoloModelTier.fromStored("LARGE"))
        assertEquals(YoloModelTier.MEDIUM, YoloModelTier.fromStored("MEDIUM"))
        assertEquals(YoloModelTier.LOW, YoloModelTier.fromStored("MEDIUM_V5_FALLBACK"))
        assertEquals(YoloModelTier.LOW, YoloModelTier.fromStored("LOW"))
        assertEquals(YoloModelTier.LITE, YoloModelTier.fromStored("LITE"))
        assertEquals(YoloModelTier.HIGH, YoloModelTier.fromStored("unknown"))
    }

    @Test
    fun `model budget is two hundred million bytes`() {
        assertEquals(200_000_000L, YoloModelTier.MAX_MODEL_BYTES)
    }

    @Test
    fun `fallback result exposes reason and actual tier`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.HIGH,
            actual = YoloModelTier.MEDIUM,
            modelFile = YoloModelTier.MEDIUM.fileName,
            failureReasons = listOf("High：运行时探测失败"),
        )
        assertTrue(result.success)
        assertTrue(result.wasFallback)
        assertTrue(result.userMessage().contains("High模型未通过设备兼容性检查"))
        assertTrue(result.userMessage().contains("Medium模型"))
    }

    @Test
    fun `high tier uses restored historical ensemble asset`() {
        assertEquals("yolov5l_xq_fp32.tflite", YoloModelTier.HIGH.fileName)
        assertTrue(YoloModelTier.HIGH.selectionHint.contains("复合"))
    }

    @Test
    fun `all four effect tiers are selectable`() {
        assertTrue(YoloModelTier.values().all { it.displayName in setOf("Lite", "Low", "Medium", "High") })
    }

    @Test
    fun `successful lite result is not a fallback`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.LITE,
            actual = YoloModelTier.LITE,
            modelFile = YoloModelTier.LITE.fileName,
        )
        assertTrue(result.success)
        assertFalse(result.wasFallback)
        assertTrue(result.userMessage().contains("已启用Lite模型"))
    }
}

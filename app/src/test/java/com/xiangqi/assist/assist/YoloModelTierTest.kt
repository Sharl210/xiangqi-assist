package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModelTierTest {
    @Test
    fun `only original V5 Medium and Lite are exposed`() {
        assertEquals(listOf("Medium", "Lite"), YoloModelTier.values().map { it.displayName })
        assertEquals("yolov5m_xq_fp32.tflite", YoloModelTier.MEDIUM.fileName)
        assertEquals("yolov5n_xq_fp16.tflite", YoloModelTier.LITE.fileName)
        assertEquals("MEDIUM", YoloModelTier.DEFAULT_NAME)
        assertEquals(YoloModelTier.MEDIUM, YoloModelTier.fromStored(null))
        assertEquals(YoloModelTier.MEDIUM, YoloModelTier.fromStored("old-value"))
        assertTrue(YoloModelTier.MEDIUM.selectionHint.isNotBlank())
        assertTrue(YoloModelTier.LITE.selectionHint.isNotBlank())
        // 实验评分属开发侧资料，不得出现在用户可读的模型说明里。
        for (tier in YoloModelTier.values()) {
            assertFalse(tier.selectionHint.contains("/100", ignoreCase = true))
            assertFalse(tier.selectionHint.contains("样本", ignoreCase = true))
        }
    }

    @Test
    fun `only runtime compatibility failure falls from Medium to Lite`() {
        assertEquals(listOf(YoloModelTier.MEDIUM, YoloModelTier.LITE), YoloModelTier.MEDIUM.fallbackOrder())
        assertEquals(listOf(YoloModelTier.LITE), YoloModelTier.LITE.fallbackOrder())
    }

    @Test
    fun `legacy four tier configurations migrate to V5 Medium except explicit Lite`() {
        for (value in listOf("LOW", "MEDIUM", "MEDIUM_V5_FALLBACK", "HIGH", "LARGE", "SUPER_LARGE", "unknown")) {
            assertEquals(value, YoloModelTier.MEDIUM, YoloModelTier.fromStored(value))
        }
        assertEquals(YoloModelTier.LITE, YoloModelTier.fromStored("LITE"))
    }

    @Test
    fun `model budget is two hundred million bytes`() {
        assertEquals(200_000_000L, YoloModelTier.MAX_MODEL_BYTES)
    }

    @Test
    fun `fallback result exposes reason and actual Lite tier`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.MEDIUM,
            actual = YoloModelTier.LITE,
            modelFile = YoloModelTier.LITE.fileName,
            failureReasons = listOf("Medium：运行时探测失败"),
        )
        assertTrue(result.success)
        assertTrue(result.wasFallback)
        assertTrue(result.userMessage().contains("Medium模型未通过设备兼容性检查"))
        assertTrue(result.userMessage().contains("Lite模型"))
    }

    @Test
    fun `successful Lite result is not a fallback`() {
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

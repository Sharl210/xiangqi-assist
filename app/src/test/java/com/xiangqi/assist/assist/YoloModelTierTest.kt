package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloModelTierTest {
    @Test
    fun `fallback order is large medium lite`() {
        assertEquals(
            listOf(YoloModelTier.LARGE, YoloModelTier.MEDIUM, YoloModelTier.LITE),
            YoloModelTier.LARGE.fallbackOrder(),
        )
        assertEquals(
            listOf(YoloModelTier.MEDIUM, YoloModelTier.LITE),
            YoloModelTier.MEDIUM.fallbackOrder(),
        )
        assertEquals(listOf(YoloModelTier.LITE), YoloModelTier.LITE.fallbackOrder())
    }

    @Test
    fun `unknown stored value uses large default`() {
        assertEquals(YoloModelTier.LARGE, YoloModelTier.fromStored(null))
        assertEquals(YoloModelTier.LARGE, YoloModelTier.fromStored("old-value"))
        assertEquals(YoloModelTier.MEDIUM, YoloModelTier.fromStored("MEDIUM"))
    }

    @Test
    fun `fallback result exposes reason and actual tier`() {
        val result = YoloModelSelectionResult(
            requested = YoloModelTier.LARGE,
            actual = YoloModelTier.MEDIUM,
            modelFile = YoloModelTier.MEDIUM.fileName,
            failureReasons = listOf("大型：资产不存在"),
        )
        assertTrue(result.success)
        assertTrue(result.wasFallback)
        assertTrue(result.userMessage().contains("大型模型未通过设备兼容性检查"))
        assertTrue(result.userMessage().contains("中型模型"))
    }

    @Test
    fun `successful result is not a fallback`() {
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

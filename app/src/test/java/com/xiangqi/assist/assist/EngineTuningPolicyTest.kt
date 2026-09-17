package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎线程 / Hash / 识别线程的自适应策略。 */
class EngineTuningPolicyTest {

    @Test
    fun `auto threads leave one core for the game and the recognizer`() {
        // 单核/双核等极端设备至少还有 1 个线程
        assertEquals(1, EngineTuningPolicy.autoThreads(0))
        assertEquals(1, EngineTuningPolicy.autoThreads(1))
        assertEquals(1, EngineTuningPolicy.autoThreads(2))
        assertEquals(3, EngineTuningPolicy.autoThreads(4))
        assertEquals(4, EngineTuningPolicy.autoThreads(5))
        assertEquals(7, EngineTuningPolicy.autoThreads(8))
        // 上限 8：再多收益递减且更容易触发降频
        assertEquals(EngineTuningPolicy.MAX_THREADS, EngineTuningPolicy.autoThreads(12))
        assertEquals(EngineTuningPolicy.MAX_THREADS, EngineTuningPolicy.autoThreads(64))
    }

    @Test
    fun `explicit thread settings are used as is within one to eight`() {
        for (n in 1..8) assertEquals(n, EngineTuningPolicy.resolveThreads(n, cores = 8))
        // 非法值退化为自动策略
        assertEquals(EngineTuningPolicy.autoThreads(8), EngineTuningPolicy.resolveThreads(0, 8))
        assertEquals(EngineTuningPolicy.autoThreads(8), EngineTuningPolicy.resolveThreads(-3, 8))
        assertEquals(EngineTuningPolicy.autoThreads(8), EngineTuningPolicy.resolveThreads(99, 8))
    }

    @Test
    fun `thread button cycles auto then one through eight then back to auto`() {
        assertEquals(1, EngineTuningPolicy.nextSetting(EngineTuningPolicy.AUTO))
        assertEquals(2, EngineTuningPolicy.nextSetting(1))
        assertEquals(8, EngineTuningPolicy.nextSetting(7))
        assertEquals(EngineTuningPolicy.AUTO, EngineTuningPolicy.nextSetting(8))
        assertEquals(EngineTuningPolicy.AUTO, EngineTuningPolicy.nextSetting(999))
        assertEquals("自动", EngineTuningPolicy.label(EngineTuningPolicy.AUTO))
        assertEquals("4", EngineTuningPolicy.label(4))
    }

    @Test
    fun `recognition threads stay between two and four`() {
        assertEquals(2, EngineTuningPolicy.recognitionThreads(1))
        assertEquals(2, EngineTuningPolicy.recognitionThreads(2))
        assertEquals(3, EngineTuningPolicy.recognitionThreads(6))
        assertEquals(4, EngineTuningPolicy.recognitionThreads(8))
        assertEquals(4, EngineTuningPolicy.recognitionThreads(16))
        for (cores in 0..16) {
            val n = EngineTuningPolicy.recognitionThreads(cores)
            assertTrue(n in EngineTuningPolicy.MIN_RECOGNITION_THREADS..EngineTuningPolicy.MAX_RECOGNITION_THREADS)
        }
    }

    @Test
    fun `assist hash default is five hundred and twelve megabytes`() {
        assertEquals(512, AssistConfig.DEFAULT_HASH_MB)
    }
    @Test
    fun `hash never exceeds the memory class ceiling`() {
        assertEquals(128, EngineTuningPolicy.resolveHashMb(2048, memoryClassMb = 96))
        assertEquals(256, EngineTuningPolicy.resolveHashMb(2048, memoryClassMb = 200))
        assertEquals(512, EngineTuningPolicy.resolveHashMb(2048, memoryClassMb = 300))
        assertEquals(1024, EngineTuningPolicy.resolveHashMb(2048, memoryClassMb = 700))
        // 请求值低于上限时按请求值使用
        assertEquals(256, EngineTuningPolicy.resolveHashMb(256, memoryClassMb = 700))
        assertEquals(16, EngineTuningPolicy.resolveHashMb(0, memoryClassMb = 700))
    }
}

package com.xiangqi.assist.assist

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistRuntimeLoggerTest {
    @Test
    fun `prepared session keeps log through start pause reset and close then next prepare clears`() {
        val dir = Files.createTempDirectory("assist-logs").toFile()
        try {
            val old = dir.resolve("old.log")
            old.writeText("old")
            val logger = AssistRuntimeLogger(dir)
            logger.startSession("one_tap_prepare")
            logger.logUserAction("activity", "一键准备")
            logger.log("RUN_START", "mode=auto")
            logger.startSession("run_start")
            logger.log("RUN_PAUSE", "manual=true")
            logger.startSession("user_reset")
            logger.log("RUN_RESET", "mode=auto")

            var text = dir.resolve("assist.log").readText()
            assertFalse(old.exists())
            assertTrue(text.contains("reason=one_tap_prepare"))
            assertTrue(text.contains("SESSION_REUSED|reason=run_start"))
            assertTrue(text.contains("SESSION_REUSED|reason=user_reset"))
            assertTrue(text.contains("USER_ACTION|surface=activity action=一键准备"))
            assertTrue(text.contains("RUN_START|mode=auto"))
            assertTrue(text.contains("RUN_PAUSE|manual=true"))
            assertTrue(text.contains("RUN_RESET|mode=auto"))
            assertEquals(1, Regex("SESSION\\|").findAll(text).count())

            logger.endSession("one_tap_close")
            logger.logUserAction("overlay", "after_close")
            text = dir.resolve("assist.log").readText()
            assertTrue(text.contains("SESSION_END|reason=one_tap_close"))
            assertFalse(text.contains("after_close"))
            assertTrue("closed session log remains available for export", dir.resolve("assist.log").exists())

            val loggerAfterProcessRestart = AssistRuntimeLogger(dir)
            loggerAfterProcessRestart.startSession("next_one_tap_prepare")
            val next = dir.resolve("assist.log").readText()
            assertTrue(next.contains("reason=next_one_tap_prepare"))
            assertFalse(next.contains("reason=one_tap_prepare"))
            assertFalse(next.contains("RUN_RESET"))
            assertEquals(1, dir.listFiles()?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `logger does not write before a prepared session starts`() {
        val dir = Files.createTempDirectory("assist-logs").toFile()
        try {
            val logger = AssistRuntimeLogger(dir)
            logger.log("IGNORED", "not-prepared")
            logger.logUserAction("overlay", "IGNORED")
            assertFalse(logger.isActive())
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `end session is idempotent and stops further writes without deleting file`() {
        val dir = Files.createTempDirectory("assist-logs").toFile()
        try {
            val logger = AssistRuntimeLogger(dir)
            logger.startSession("prepare")
            logger.log("STATE", "RUNNING")
            logger.endSession("one_tap_close")
            logger.endSession("service_destroy")
            logger.log("STATE", "SHOULD_NOT_APPEAR")
            val text = dir.resolve("assist.log").readText()
            assertTrue(text.contains("STATE|RUNNING"))
            assertFalse(text.contains("SHOULD_NOT_APPEAR"))
            assertEquals(1, Regex("SESSION_END\\|").findAll(text).count())
            assertTrue(dir.resolve("assist.log").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}

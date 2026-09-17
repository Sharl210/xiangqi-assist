package com.xiangqi.assist.assist

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistRuntimeLoggerTest {
    @Test
    fun `new session clears old log files and records new session`() {
        val dir = Files.createTempDirectory("assist-logs").toFile()
        try {
            val old = dir.resolve("old.log")
            old.writeText("old")
            val logger = AssistRuntimeLogger(dir)
            logger.startSession("first")
            logger.log("STATE", "READY")
            assertFalse(old.exists())
            assertTrue(dir.resolve("assist.log").readText().contains("reason=first"))
            assertTrue(dir.resolve("assist.log").readText().contains("STATE|READY"))

            logger.startSession("second")
            val text = dir.resolve("assist.log").readText()
            assertTrue(text.contains("reason=second"))
            assertFalse(text.contains("reason=first"))
            assertFalse(text.contains("STATE|READY"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `logger does not write before a session starts`() {
        val dir = Files.createTempDirectory("assist-logs").toFile()
        try {
            val logger = AssistRuntimeLogger(dir)
            logger.log("IGNORED", "not-started")
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}

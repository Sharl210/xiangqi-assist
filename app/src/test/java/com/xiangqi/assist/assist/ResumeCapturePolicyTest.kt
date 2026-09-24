package com.xiangqi.assist.assist

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCapturePolicyTest {
    @Test
    fun `resume grant waits until the target package becomes foreground`() {
        val grant = ResumeCapturePolicy.Grant(resultCode = 7, data = Intent("capture"))
        assertTrue(ResumeCapturePolicy.shouldStartCapture(
            grant = grant,
            targetPackage = "com.game",
            currentPackage = "com.xiangqi.assist",
        ).not())
        assertTrue(ResumeCapturePolicy.shouldStartCapture(
            grant = grant,
            targetPackage = "com.game",
            currentPackage = "com.game",
        ))
    }

    @Test
    fun `no grant or no original package never starts capture`() {
        assertFalse(ResumeCapturePolicy.shouldStartCapture(null, "com.game", "com.game"))
        assertFalse(ResumeCapturePolicy.shouldStartCapture(
            ResumeCapturePolicy.Grant(7, Intent("capture")),
            null,
            "com.game",
        ))
    }

    @Test
    fun `grant stores a defensive intent copy`() {
        val source = Intent("capture")
        val grant = ResumeCapturePolicy.Grant(7, source)
        assertNotNull(grant.data)
        assertTrue(grant.data !== source)
    }
}

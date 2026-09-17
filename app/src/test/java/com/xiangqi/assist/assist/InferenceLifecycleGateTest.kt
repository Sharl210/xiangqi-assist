package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InferenceLifecycleGateTest {
    @Test
    fun `close waits for in-flight inference and rejects later inference`() {
        val gate = InferenceLifecycleGate()
        val entered = CountDownLatch(1)
        val releaseInference = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val worker = Thread {
            gate.runIfOpen {
                entered.countDown()
                releaseInference.await(2, TimeUnit.SECONDS)
                7
            }
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val closeDone = CountDownLatch(1)
        val closer = Thread {
            gate.close {
                released.set(true)
                closeDone.countDown()
            }
        }
        closer.start()
        assertTrue("close must wait for the in-flight run", !closeDone.await(100, TimeUnit.MILLISECONDS))
        assertNull(gate.runIfOpen { 9 })
        releaseInference.countDown()
        assertTrue(closeDone.await(1, TimeUnit.SECONDS))
        worker.join(1000)
        assertTrue(released.get())
        assertTrue(gate.isClosed)
    }
}

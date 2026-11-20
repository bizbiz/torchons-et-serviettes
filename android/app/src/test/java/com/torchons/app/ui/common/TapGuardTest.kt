package com.torchons.app.ui.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap on a task whose completion is still being handled is ignored (2026-11-19: 3 to 4 taps in
 * a row on one task).
 *
 * PATHS: (1) second tap while the first is held: refused .... COVERED; (2) another task: free
 * .... COVERED; (3) released after the celebration: free again .... COVERED; (4) many taps from
 * several threads: exactly one gets through .... COVERED; (5) the ViewModels releasing it at the
 * end of the celebration and on a refusal: not covered (they need Android).
 */
class TapGuardTest {

    @Test
    fun `a second tap on the same task is refused until the first is released`() {
        val guard = TapGuard()
        assertTrue(guard.tryAcquire("a"))
        assertFalse(guard.tryAcquire("a"))
        assertTrue(guard.tryAcquire("b"))
        guard.release("a")
        assertTrue(guard.tryAcquire("a"))
    }

    @Test
    fun `eight simultaneous taps let exactly one through`() {
        val guard = TapGuard()
        val through = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                if (guard.tryAcquire("a")) through.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(1, through.get())
    }
}

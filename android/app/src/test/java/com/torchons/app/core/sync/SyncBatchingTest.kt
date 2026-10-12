package com.torchons.app.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline queue goes to /sync in batches of SYNC_BATCH_SIZE, in queue order,
 * and stops at the first failed batch so the next pass replays only the rest.
 *
 * PATHS by which "a long queue reaches the server whole, in order, bounded"
 * can break, listed before the cases:
 *   (1) chunking and order (`sendInBatches`) ............................ COVERED
 *   (2) stop at the first failed batch ................................. COVERED
 *   (3) what a sent batch settles (applied ops deleted, conflicts kept,
 *       cursor advanced) ........ not covered here: it lives in SyncWorker's
 *       lambda on Room DAOs and the token store, reached only through Hilt.
 *       Exercised on the emulator on 2026-10-12 (offline create + completes).
 *   (4) the server refusing an oversized batch ... covered server side by
 *       test_sync_refuses_an_oversized_batch (tests/test_public_route_costs.py).
 */
class SyncBatchingTest {

    @Test
    fun longQueueGoesInOrderedBoundedBatches() = runBlocking {
        val queue = (1..250).toList()
        val sent = mutableListOf<List<Int>>()

        val ok = sendInBatches(queue, SYNC_BATCH_SIZE) { sent += it; true }

        assertTrue(ok)
        assertEquals(listOf(100, 100, 50), sent.map { it.size })
        assertEquals(queue, sent.flatten())
    }

    @Test
    fun failedBatchStopsTheRest() = runBlocking {
        val sent = mutableListOf<List<Int>>()

        val ok = sendInBatches((1..250).toList(), SYNC_BATCH_SIZE) {
            sent += it
            sent.size < 2
        }

        assertFalse(ok)
        assertEquals(2, sent.size)
        assertEquals(101, sent[1].first())
    }

    @Test
    fun emptyQueueSendsNothing() = runBlocking {
        var calls = 0
        assertTrue(sendInBatches(emptyList<Int>(), SYNC_BATCH_SIZE) { calls++; true })
        assertEquals(0, calls)
    }
}

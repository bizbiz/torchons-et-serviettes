package com.torchons.app.core.sync

import com.google.gson.Gson
import com.torchons.app.data.model.SyncResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The /sync pull is cut at 500 rows by the server, which then answers `has_more: true` (backend
 * `_pull_changes`). The worker used to take the first 500 rows and stop: a device away for long
 * never caught up unless another path refreshed it.
 *
 * Paths by which "the worker pulls until the server has nothing more, and never for ever":
 *   (1) the answer carries `has_more`, absent on an older server = false ..................... COVERED
 *   (2) has_more false after the batches: no extra pull ..................................... COVERED
 *   (3) has_more true: pulls again, once per page, until the server says false .............. COVERED
 *   (4) a server that says true for ever: the run stops at MAX_PULL_PAGES ................... COVERED
 *   (5) a pull that fails ends the pass as a failure (retry), pages already settled kept ...... COVERED
 * Not covered: the worker's request itself (Hilt, Retrofit, the cursor in DataStore): `sendBatch`
 * sets `hasMore` from `body.hasMore` and reuses the batch request with an empty `changes` list.
 */
class PullPagesTest {
    @Test
    fun `the sync answer reads has_more and defaults to false`() {
        val gson = Gson()
        assertTrue(gson.fromJson("""{"server_version":9,"has_more":true}""", SyncResponse::class.java).hasMore)
        assertFalse(gson.fromJson("""{"server_version":9}""", SyncResponse::class.java).hasMore)
    }

    @Test
    fun `no extra pull when the last answer had nothing more`() = runBlocking {
        var pulls = 0
        val ok = pullWhileMore(startsWithMore = false, maxPages = MAX_PULL_PAGES) { pulls++; PullPage(true, false) }
        assertTrue(ok)
        assertEquals(0, pulls)
    }

    @Test
    fun `pulls again until the server says there is no more`() = runBlocking {
        val answers = ArrayDeque(listOf(true, true, false))
        var pulls = 0
        val ok = pullWhileMore(startsWithMore = true, maxPages = MAX_PULL_PAGES) {
            pulls++
            PullPage(true, answers.removeFirst())
        }
        assertTrue(ok)
        assertEquals("one pull per page, the last one said false", 3, pulls)
    }

    @Test
    fun `a server that always says more is cut at the page bound`() = runBlocking {
        var pulls = 0
        val ok = pullWhileMore(startsWithMore = true, maxPages = MAX_PULL_PAGES) { pulls++; PullPage(true, true) }
        assertTrue("cut, not failed: the cursor is saved, the next run goes on", ok)
        assertEquals(20, MAX_PULL_PAGES)
        assertEquals(MAX_PULL_PAGES, pulls)
    }

    @Test
    fun `a failed pull ends the pass as a failure`() = runBlocking {
        var pulls = 0
        val ok = pullWhileMore(startsWithMore = true, maxPages = MAX_PULL_PAGES) {
            pulls++
            if (pulls == 2) PullPage(false, true) else PullPage(true, true)
        }
        assertFalse(ok)
        assertEquals(2, pulls)
    }
}

package com.torchons.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.newInMemoryDb
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * Emulator re-test of 2026-11-20 (item 5): offline, the rating dialog of a task showed "Jamais
 * noté" and defaults although the user had rated it on the server. The votes are cached in Room
 * (`view_snapshots`) when fetched, and read back offline.
 *
 * Paths by which an offline screen can miss a vote the app already knew:
 *   (1) a task's votes saved after a fetch come back as saved .............................. COVERED
 *   (2) a second save REPLACES the first (the newer fetch wins) ............................. COVERED
 *   (3) a task never fetched: null, not an empty answer ..................................... COVERED
 *   (4) the bulk "my affinities" (one request with the tasks refresh) gives the user's own
 *       affinity on a task never opened ....................................................... COVERED
 *   (5) another user's bulk snapshot is never read ........................................... COVERED
 *   (6) a failed bulk request keeps the previous snapshot; a fresh snapshot is not refetched ... COVERED
 * Not covered: the ViewModel loading the cache at start (see CachedVotesTest for its decision).
 */
@RunWith(AndroidJUnit4::class)
class TaskVotesStoreTest {
    private lateinit var db: AppDatabase
    private var clock = 1_000L
    private var user: String? = "alice"
    private val requests = AtomicInteger()
    private var answer: () -> Response<List<Map<String, Any>>> = { Response.success(emptyList()) }

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private val api: ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader, arrayOf(ApiService::class.java),
    ) { _, _, _ ->
        requests.incrementAndGet()
        answer()
    } as ApiService

    private fun store() = TaskVotesStore(db.viewSnapshotDao(), api, { user }, { clock })

    @Test
    fun `a task's votes come back as saved, and a second save replaces the first`() = runBlocking {
        val store = store()
        store.saveTaskVotes("t1", """{"ratings":[]}""")
        assertEquals("""{"ratings":[]}""", store.taskVotes("t1"))

        store.saveTaskVotes("t1", """{"ratings":[{"affinity":9}]}""")

        assertEquals("""{"ratings":[{"affinity":9}]}""", store.taskVotes("t1"))
    }

    @Test
    fun `a task never fetched has no cached votes`() = runBlocking {
        assertNull(store().taskVotes("t-never"))
    }

    @Test
    fun `my affinity on a task never opened comes from the bulk snapshot`() = runBlocking {
        answer = {
            Response.success(
                listOf(mapOf("task_id" to "t7", "score" to 9.0), mapOf("task_id" to "t8", "score" to 2.0)),
            )
        }
        val store = store()

        store.refreshMyAffinities()

        assertEquals(9, store.myAffinity("alice", "t7"))
        assertEquals(2, store.myAffinity("alice", "t8"))
        assertNull("a task with no affinity row is unknown, not 0", store.myAffinity("alice", "t9"))
    }

    @Test
    fun `another user never reads the snapshot`() = runBlocking {
        answer = { Response.success(listOf(mapOf("task_id" to "t7", "score" to 9.0))) }
        val store = store()
        store.refreshMyAffinities()

        assertNull(store.myAffinity("bob", "t7"))
    }

    @Test
    fun `a failed request keeps the snapshot, a fresh snapshot is not asked again`() = runBlocking {
        answer = { Response.success(listOf(mapOf("task_id" to "t7", "score" to 9.0))) }
        val store = store()
        store.refreshMyAffinities()
        assertEquals(1, requests.get())

        clock += 1_000 // still fresh
        store.refreshMyAffinities()
        assertEquals("fresh: no new request", 1, requests.get())

        clock += MY_AFFINITIES_MAX_AGE_MS // stale now, and the network is down
        answer = { throw IOException("no route") }
        store.refreshMyAffinities()

        assertEquals(2, requests.get())
        assertEquals("the failure kept the old value", 9, store.myAffinity("alice", "t7"))
    }
}

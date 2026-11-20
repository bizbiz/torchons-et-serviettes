package com.torchons.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.core.sync.settleApplied
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import com.torchons.app.data.local.taskRow
import com.torchons.app.data.model.SyncAppliedOp
import com.torchons.app.data.model.Task
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * `refreshTasksCache` (what `TaskRepository.refreshTasks` runs) on a real Room, with the real
 * [settleApplied] playing the worker (D1, 2026-11-20 audit).
 *
 * Paths by which a tasks refresh can erase or revert a task:
 *   (1) the request leaves before the worker pushes + settles a task INSERT, the answer lacks the
 *       task and is applied after: must be DROPPED, the row survives ................ COVERED
 *   (2) same for an edit: the stale answer must not revert it ........................ COVERED
 *   (3) no push in between: REPLACE semantics, a server-deleted task goes ........... COVERED
 *   (4) the retry is stale too: nothing applied, false returned ...................... COVERED
 *   (5) a failed request (HTTP error, network): cache untouched, false ............... COVERED
 *   (6) a pending (not yet pushed) INSERT is spared by the DAO under the guard ....... COVERED
 * Not covered: the Retrofit call itself (`ApiService.getTasks`), one lambda handed in here.
 */
@RunWith(AndroidJUnit4::class)
class TasksRefreshRoomTest {
    private lateinit var db: AppDatabase
    private val guard = CompletionRefreshGuard()

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private fun answer(vararg titles: Pair<String, String>): Response<List<Task>> =
        Response.success(titles.map { (id, title) -> taskRow(id, title).toDomain() })

    private suspend fun cachedTitles() = db.taskDao().observeAll().first().associate { it.id to it.title }

    /** The worker settling a pushed task op: queue delete + epoch bump, as in SyncWorker. */
    private suspend fun workerSettles(opId: String, operation: String, rowId: String) {
        val op = pendingOp(opId, "tasks", operation, rowId)
        settleApplied(
            guard, listOf(SyncAppliedOp(opId, rowId, 1)), listOf(op),
            deleteOp = db.pendingOperationDao()::delete,
            deleteCompletionRow = db.completionDao()::deleteById,
        )
    }

    @Test
    fun `an answer sent before a settled push is dropped, the created task survives`() = runBlocking {
        db.taskDao().upsert(taskRow("new", title = "created offline"))
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "INSERT", "new"))
        val requests = AtomicInteger()

        val ok = refreshTasksCache(guard, db.taskDao()) {
            if (requests.incrementAndGet() == 1) {
                // The server answered BEFORE the push (no "new"), then the push landed and settled.
                val stale = answer("old" to "server old")
                workerSettles("op-1", "INSERT", "new")
                stale
            } else {
                answer("old" to "server old", "new" to "created offline")
            }
        }

        assertTrue(ok)
        assertEquals(2, requests.get())
        assertEquals(mapOf("old" to "server old", "new" to "created offline"), cachedTitles())
    }

    @Test
    fun `an answer sent before a settled edit does not revert it`() = runBlocking {
        db.taskDao().upsert(taskRow("t1", title = "edited"))
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "UPDATE", "t1"))
        val requests = AtomicInteger()

        refreshTasksCache(guard, db.taskDao()) {
            if (requests.incrementAndGet() == 1) {
                val stale = answer("t1" to "before the edit")
                workerSettles("op-1", "UPDATE", "t1")
                stale
            } else {
                answer("t1" to "edited")
            }
        }

        assertEquals(mapOf("t1" to "edited"), cachedTitles())
    }

    @Test
    fun `with no push in between the server wins and a deleted task goes`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("gone"), taskRow("kept", title = "old")))

        val ok = refreshTasksCache(guard, db.taskDao()) { answer("kept" to "fresh") }

        assertTrue(ok)
        assertEquals(mapOf("kept" to "fresh"), cachedTitles())
    }

    @Test
    fun `two stale answers in a row leave the cache alone and report false`() = runBlocking {
        db.taskDao().upsert(taskRow("kept", title = "local"))
        val requests = AtomicInteger()

        val ok = refreshTasksCache(guard, db.taskDao()) {
            workerSettles("op-${requests.incrementAndGet()}", "UPDATE", "kept")
            answer("other" to "server")
        }

        assertFalse(ok)
        assertEquals(2, requests.get())
        assertEquals(mapOf("kept" to "local"), cachedTitles())
    }

    @Test
    fun `a failed request leaves the cache alone and reports false`() = runBlocking {
        db.taskDao().upsert(taskRow("kept"))

        val http = refreshTasksCache(guard, db.taskDao()) { Response.error(500, "".toResponseBody()) }
        val network = refreshTasksCache(guard, db.taskDao()) { throw IOException("offline") }

        assertFalse(http)
        assertFalse(network)
        assertEquals(setOf("kept"), cachedTitles().keys)
    }

    @Test
    fun `a task whose INSERT is still queued survives a refresh that was not stale`() = runBlocking {
        db.taskDao().upsert(taskRow("new", title = "created offline"))
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "INSERT", "new"))

        refreshTasksCache(guard, db.taskDao()) { answer("old" to "server old") }

        assertEquals(setOf("old", "new"), cachedTitles().keys)
    }
}

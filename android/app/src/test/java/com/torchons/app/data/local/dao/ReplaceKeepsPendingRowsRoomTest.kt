package com.torchons.app.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.completionRow
import com.torchons.app.data.local.delegationRow
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import com.torchons.app.data.local.taskRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL SQL (Room in memory, Robolectric) of the three "replace the scope, spare what a queued op
 * still has to push" DAO functions: `TaskDao.replaceAll` (D1, 2026-11-20 audit: it was
 * deleteAll + upsertAll and erased unpushed tasks), `CompletionDao.replaceAll` / `replaceForTask`
 * and `DelegationDao.replaceAll`.
 *
 * Paths by which "a refresh never erases, edits or resurrects a row a queued op owns" can break:
 *   (1) a row with a pending INSERT is deleted by the replace (task created offline) ..... COVERED
 *   (2) a row with a pending UPDATE is overwritten by the server's old copy ............... COVERED
 *   (3) a row with a pending DELETE is brought back by the server's copy .................. COVERED
 *   (4) a synced row the server no longer has stays (REPLACE semantics lost) .............. COVERED
 *   (5) an op on ANOTHER table spares a row of this one (the subquery ignores tableName) ... COVERED
 *   (6) replaceForTask touches rows of other tasks ........................................ COVERED
 * Not covered: the 'NOT IN' subquery with a NULL rowId (rowId is a non-null column); the
 * tasks epoch guard (TasksRefreshRoomTest).
 */
@RunWith(AndroidJUnit4::class)
class ReplaceKeepsPendingRowsRoomTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private suspend fun taskTitles() = db.taskDao().observeAll().first().associate { it.id to it.title }

    @Test
    fun `tasks replace keeps a task whose INSERT is still queued`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("synced"), taskRow("new", title = "created offline")))
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "INSERT", "new"))

        db.taskDao().replaceAll(listOf(taskRow("synced")))

        assertEquals(mapOf("synced" to "task synced", "new" to "created offline"), taskTitles())
    }

    @Test
    fun `tasks replace keeps the local edit of a task whose UPDATE is still queued`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("t1", title = "edited offline")))
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "UPDATE", "t1"))

        db.taskDao().replaceAll(listOf(taskRow("t1", title = "old server title")))

        assertEquals("edited offline", db.taskDao().getById("t1")?.title)
    }

    @Test
    fun `tasks replace does not bring back a task whose DELETE is still queued`() = runBlocking {
        db.pendingOperationDao().insert(pendingOp("op-1", "tasks", "DELETE", "gone"))

        db.taskDao().replaceAll(listOf(taskRow("gone"), taskRow("kept")))

        assertNull(db.taskDao().getById("gone"))
        assertEquals(setOf("kept"), taskTitles().keys)
    }

    @Test
    fun `tasks replace still removes a synced task the server no longer has`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("old"), taskRow("kept")))

        db.taskDao().replaceAll(listOf(taskRow("kept", title = "fresh")))

        assertEquals(mapOf("kept" to "fresh"), taskTitles())
    }

    @Test
    fun `a queued op on another table does not spare a task`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("t1")))
        db.pendingOperationDao().insert(pendingOp("op-1", "task_completions", "INSERT", "t1"))

        db.taskDao().replaceAll(emptyList())

        assertNull(db.taskDao().getById("t1"))
    }

    @Test
    fun `completions replaceAll keeps pending rows and skips server copies of pending ids`() = runBlocking {
        db.completionDao().upsertAll(
            listOf(
                completionRow("synced"),
                completionRow("pending-ins", notes = "local"),
                completionRow("pending-upd", notes = "local edit"),
            ),
        )
        db.pendingOperationDao().insert(pendingOp("op-1", "task_completions", "INSERT", "pending-ins"))
        db.pendingOperationDao().insert(pendingOp("op-2", "task_completions", "UPDATE", "pending-upd"))
        db.pendingOperationDao().insert(pendingOp("op-3", "task_completions", "DELETE", "pending-del"))

        db.completionDao().replaceAll(
            listOf(
                completionRow("fresh"),
                completionRow("pending-upd", notes = "old server"),
                completionRow("pending-del"),
            ),
        )

        val rows = db.completionDao().observeRecent().first().associate { it.id to it.notes }
        assertEquals(setOf("fresh", "pending-ins", "pending-upd"), rows.keys)
        assertEquals("local", rows["pending-ins"])
        assertEquals("local edit", rows["pending-upd"])
    }

    @Test
    fun `completions replaceForTask only touches its task`() = runBlocking {
        db.completionDao().upsertAll(
            listOf(
                completionRow("a1", taskId = "A"),
                completionRow("a2", taskId = "A"),
                completionRow("b1", taskId = "B"),
            ),
        )
        db.pendingOperationDao().insert(pendingOp("op-1", "task_completions", "INSERT", "a2"))

        db.completionDao().replaceForTask("A", listOf(completionRow("a3", taskId = "A")))

        val ids = db.completionDao().observeRecent().first().map { it.id }.toSet()
        assertEquals(setOf("a2", "a3", "b1"), ids)
    }

    @Test
    fun `delegations replaceAll keeps a queued hand-over, skips a queued cancellation`() = runBlocking {
        db.delegationDao().upsertAll(listOf(delegationRow("synced"), delegationRow("handed")))
        db.pendingOperationDao().insert(pendingOp("op-1", "task_delegations", "INSERT", "handed"))
        db.pendingOperationDao().insert(pendingOp("op-2", "task_delegations", "DELETE", "cancelled"))

        db.delegationDao().replaceAll(listOf(delegationRow("fresh"), delegationRow("cancelled")))

        val ids = db.delegationDao().observeActive().first().map { it.id }.toSet()
        assertEquals(setOf("fresh", "handed"), ids)
    }
}

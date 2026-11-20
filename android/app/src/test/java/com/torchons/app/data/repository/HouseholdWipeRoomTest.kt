package com.torchons.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.completionRow
import com.torchons.app.data.local.delegationRow
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.FormerMemberEntity
import com.torchons.app.data.local.entities.TaskHistoryEntity
import com.torchons.app.data.local.entities.UserEntity
import com.torchons.app.data.local.entities.ViewSnapshotEntity
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import com.torchons.app.data.local.taskRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D5 (2026-11-20 audit): the household switch wiped four DAOs and left delegations, view
 * snapshots and task history of the previous household on screen.
 *
 * Paths by which "nothing of the previous household survives, the unsent work does" can break:
 *   (1) a table is missing from the wipe: delegations, view_snapshots, task_history, former_members
 *       were. The test seeds EVERY table of the database, read from sqlite_master, so a table
 *       added later fails here until it is seeded (and then proves it is wiped) ........ COVERED
 *   (2) the queue is wiped too: the ops of household A would be lost ...................... COVERED
 *   (3) a row with a pending op survives (the replace-style wipe kept them) ............... COVERED
 *   (4) the wipe is not atomic ...................................................... not covered:
 *       one `withTransaction` around plain DELETEs; a half-wiped state needs a process kill.
 * Not covered: Room's own bookkeeping tables (room_master_table, android_metadata): left alone by
 * design, not data.
 */
@RunWith(AndroidJUnit4::class)
class HouseholdWipeRoomTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private suspend fun seedEveryTable() {
        db.taskDao().upsertAll(listOf(taskRow("t1"), taskRow("t-pending")))
        db.completionDao().upsertAll(listOf(completionRow("c1"), completionRow("c-pending")))
        db.delegationDao().upsertAll(listOf(delegationRow("d1")))
        db.userDao().upsertAll(listOf(UserEntity("u1", "bob", "Bob")))
        db.formerMemberDao().upsertAll(listOf(FormerMemberEntity("f1", "old", "Old", false, "2026-10-01")))
        db.contextDao().upsert(
            ContextEntity("ctx1", "Kitchen", "#fff", null, null, false, false, 0, null, 1, null),
        )
        db.viewSnapshotDao().upsert(ViewSnapshotEntity("home_suggestions", "{}", 1L))
        db.taskHistoryDao().upsertAll(
            listOf(TaskHistoryEntity(1L, "t1", "EDIT", null, "Bob", null, null, null, null, null, null, null)),
        )
        // The unsent work of household A, with a row of its own for each op.
        db.pendingOperationDao().insert(pendingOp("op-t", "tasks", "UPDATE", "t-pending", foyerId = "A"))
        db.pendingOperationDao().insert(pendingOp("op-c", "task_completions", "INSERT", "c-pending", foyerId = "A"))
    }

    private fun rowsPerTable(): Map<String, Int> {
        val sqlite = db.openHelper.readableDatabase
        val names = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return names.associateWith { name ->
            sqlite.query("SELECT COUNT(*) FROM `$name`").use { it.moveToFirst(); it.getInt(0) }
        }
    }

    @Test
    fun `the wipe empties every table but the queue, which keeps its ops`() = runBlocking {
        seedEveryTable()
        val before = rowsPerTable()
        assertTrue("a table is not seeded by this test: $before", before.values.all { it > 0 })

        db.wipeHouseholdCaches()

        val after = rowsPerTable()
        assertEquals(before.keys, after.keys)
        for ((table, count) in after) {
            if (table == QUEUE_TABLE) assertEquals(2, count) else assertEquals("table $table", 0, count)
        }
        assertEquals(setOf("op-t", "op-c"), db.pendingOperationDao().getAll().map { it.clientOpId }.toSet())
    }

    @Test
    fun `rows that a queued op owns are wiped too, unlike a refresh`() = runBlocking {
        seedEveryTable()

        db.wipeHouseholdCaches()

        assertEquals(null, db.taskDao().getById("t-pending"))
        assertEquals(null, db.completionDao().getById("c-pending"))
    }

    @Test
    fun `the tables read from the database are the ones the app declares`() {
        val tables = rowsPerTable().keys
        assertEquals(
            setOf(
                "tasks", "users", "completions", "view_snapshots", "pending_operations", "task_history",
                "contexts", "former_members", "delegations",
            ),
            tables,
        )
    }
}

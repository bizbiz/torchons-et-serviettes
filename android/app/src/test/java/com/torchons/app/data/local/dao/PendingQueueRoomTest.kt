package com.torchons.app.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.core.sync.PROMPT_RESOLUTIONS_TABLE
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a REAL Room database: the two reads of the offline queue that the audit of 2026-11-20 made
 * user-visible.
 *  - D9: the sign-out dialog shows `observeCount()` as « N modifications ne sont pas encore
 *    envoyées », and « Synchroniser d'abord » waits for it to reach zero;
 *  - D6: `rowIdsOf(PROMPT_RESOLUTIONS_TABLE)` lets the home screen skip a prompt whose answer is
 *    queued (the server still lists it until the worker sends the answer).
 *
 * Paths by which "what the user is told about the queue is what the queue holds":
 *   (1) the count counts EVERY kind of queued op (task, vote, answer, setting) ............ COVERED
 *   (2) the count falls to zero when the worker deletes them ............................... COVERED
 *   (3) `rowIdsOf` returns the ids of ONE table only ....................................... COVERED
 * Not covered: the Compose dialog and the worker's deletes themselves.
 */
@RunWith(AndroidJUnit4::class)
class PendingQueueRoomTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    @Test
    fun the_count_covers_every_kind_of_op_and_falls_to_zero() = runBlocking {
        val dao = db.pendingOperationDao()
        assertEquals(0, dao.observeCount().first())

        dao.insert(pendingOp("c1", "tasks", "UPDATE", "t1"))
        dao.insert(pendingOp("c2", "task_effort_votes", "VOTE", "t1"))
        dao.insert(pendingOp("c3", PROMPT_RESOLUTIONS_TABLE, "RESOLVE", "p1"))
        dao.insert(pendingOp("c4", "user_settings", "UPDATE", "u1"))
        assertEquals(4, dao.observeCount().first())

        listOf("c1", "c2", "c3", "c4").forEach { dao.delete(it) }
        assertEquals(0, dao.observeCount().first())
    }

    @Test
    fun rowIdsOf_returns_the_ids_of_one_table_only() = runBlocking {
        val dao = db.pendingOperationDao()
        dao.insert(pendingOp("c1", PROMPT_RESOLUTIONS_TABLE, "RESOLVE", "p1"))
        dao.insert(pendingOp("c2", PROMPT_RESOLUTIONS_TABLE, "RESOLVE", "p2"))
        dao.insert(pendingOp("c3", "task_effort_votes", "VOTE", "p1"))

        assertEquals(setOf("p1", "p2"), dao.rowIdsOf(PROMPT_RESOLUTIONS_TABLE).toSet())
        assertEquals(listOf("p1"), dao.rowIdsOf("task_effort_votes"))
        assertEquals(emptyList<String>(), dao.rowIdsOf("tasks"))
    }
}

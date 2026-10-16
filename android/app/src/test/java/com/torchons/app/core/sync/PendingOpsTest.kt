package com.torchons.app.core.sync

import com.torchons.app.data.local.entities.PendingOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sync dialog lists the queue readably, and discarding an op drops what
 * depends on it.
 *
 * PATHS by which "the user sees why an op is stuck and can drop it safely"
 * can break, listed before the cases:
 *   (1) the server's reason reaches the row (`readableSyncError`) ....... COVERED
 *       — SyncConflict.detail stored by SyncWorker: not covered, Room + Hilt.
 *   (2) a row names its task and member (`describePendingOps`) .......... COVERED
 *   (3) discarding an INSERT drops its dependents (`opsToDiscard`) ...... COVERED
 *   (4) discarding an UPDATE/DELETE drops it alone ...................... COVERED
 *   (5) the local undo after a discard (TaskRepository.discardPendingOp:
 *       delete the INSERTed row, refresh for UPDATE/DELETE) .. not covered
 *       here: Room + network. To check on a phone.
 */
class PendingOpsTest {

    private fun op(
        id: String,
        table: String,
        operation: String,
        row: String,
        payload: String = "null",
        at: Long,
        error: String? = null,
    ) = PendingOperationEntity(
        clientOpId = id, tableName = table, operation = operation, rowId = row,
        payloadJson = payload, createdAt = at, attemptCount = if (error != null) 3 else 0,
        lastError = error,
    )

    @Test
    fun serverValidationErrorLosesItsTechnicalPrefix() {
        assertEquals(
            "Membres hors du foyer : abc",
            readableSyncError("LocalizedHTTPException: 400: Membres hors du foyer : abc"),
        )
        assertEquals("op_error", readableSyncError("op_error"))
        assertEquals("HTTP 502", readableSyncError("HTTP 502"))
        assertNull(readableSyncError(null))
    }

    @Test
    fun completionRowNamesTaskAndMember() {
        val completion = op(
            "c1", "task_completions", "INSERT", "row1",
            payload = """{"task_id":"towels","completed_by":"bob"}""",
            at = 1L, error = "LocalizedHTTPException: 400: refusé",
        )
        val row = describePendingOps(
            listOf(completion),
            taskTitles = mapOf("towels" to "Changer les serviettes de bain"),
            memberNames = mapOf("bob" to "Bob"),
        ).single()
        assertEquals(PendingOpKind.COMPLETION_ADD, row.kind)
        assertEquals("Changer les serviettes de bain", row.taskTitle)
        assertEquals("Bob", row.memberName)
        assertEquals("refusé", row.error)
    }

    @Test
    fun discardingATaskCreationDropsWhatWasQueuedOnIt() {
        val create = op("t1", "tasks", "INSERT", "task", at = 1L)
        val edit = op("t2", "tasks", "UPDATE", "task", at = 2L)
        val done = op("c1", "task_completions", "INSERT", "row", payload = """{"task_id":"task"}""", at = 3L)
        val vote = op("v1", "task_frequency_votes", "VOTE", "task", at = 4L)
        val unrelated = op("c2", "task_completions", "INSERT", "row2", payload = """{"task_id":"other"}""", at = 5L)
        val queue = listOf(create, edit, done, vote, unrelated)

        assertEquals(
            listOf("t1", "t2", "c1", "v1"),
            opsToDiscard(queue, create).map { it.clientOpId },
        )
    }

    @Test
    fun discardingACompletionDropsItsLaterEditOnly() {
        val add = op("c1", "task_completions", "INSERT", "row", at = 1L)
        val edit = op("c2", "task_completions", "UPDATE", "row", at = 2L)
        val other = op("c3", "task_completions", "INSERT", "row2", at = 3L)

        assertEquals(listOf("c1", "c2"), opsToDiscard(listOf(add, edit, other), add).map { it.clientOpId })
    }

    @Test
    fun discardingAnEditDropsItAlone() {
        val edit = op("t2", "tasks", "UPDATE", "task", at = 2L)
        val later = op("t3", "tasks", "DELETE", "task", at = 3L)

        assertEquals(listOf("t2"), opsToDiscard(listOf(edit, later), edit).map { it.clientOpId })
    }
}

package com.torchons.app.core.sync

import com.google.gson.Gson
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.SyncAppliedOp
import com.torchons.app.data.model.SyncResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server acknowledges a duplicate completion without writing it and says so
 * (`dropped: true` in `applied`); the phone must then delete the optimistic row it wrote.
 *
 * PATHS by which a duplicate completion stays on the phone:
 *   (1) the flag is not read from the JSON ........................................ COVERED
 *   (2) an older server omits the flag: nothing may be deleted ..................... COVERED
 *   (3) the dropped op is a completion INSERT: its row goes; other ops stay ....... COVERED
 *   (4) the deletion itself (`completionDao.deleteById` in SyncWorker.doWork): not covered,
 *       Room DAO behind Hilt; one line after the pure selection tested here.
 */
class DroppedOpsTest {

    private fun op(id: String, table: String, operation: String, row: String) = PendingOperationEntity(
        clientOpId = id, tableName = table, operation = operation, rowId = row,
        payloadJson = "{}", createdAt = 0L,
    )

    private val batch = listOf(
        op("op-1", "task_completions", "INSERT", "row-1"),
        op("op-2", "task_completions", "INSERT", "row-2"),
        op("op-3", "task_completions", "DELETE", "row-3"),
        op("op-4", "tasks", "INSERT", "row-4"),
    )

    @Test
    fun `the dropped completion INSERT loses its local row, the kept one stays`() {
        val applied = listOf(
            SyncAppliedOp("op-1", "row-1", 0, dropped = true),
            SyncAppliedOp("op-2", "row-2", 7),
        )
        assertEquals(listOf("row-1"), droppedCompletionRowIds(applied, batch))
    }

    @Test
    fun `only a completion INSERT is deleted locally`() {
        val applied = listOf(
            SyncAppliedOp("op-3", "row-3", 0, dropped = true), // a DELETE: nothing local to remove
            SyncAppliedOp("op-4", "row-4", 0, dropped = true), // a task: not a completion
        )
        assertTrue(droppedCompletionRowIds(applied, batch).isEmpty())
    }

    @Test
    fun `an older server omits the flag, which reads as not dropped`() {
        val json = """{"server_version": 9, "applied": [
            {"client_op_id": "op-1", "server_row_id": "row-1", "new_version": 0},
            {"client_op_id": "op-2", "server_row_id": "row-2", "new_version": 8, "dropped": true}]}"""
        val applied = Gson().fromJson(json, SyncResponse::class.java).applied

        assertFalse(applied[0].dropped)
        assertTrue(applied[1].dropped)
        assertEquals(listOf("row-2"), droppedCompletionRowIds(applied, batch))
        assertTrue(droppedCompletionRowIds(applied.take(1), batch).isEmpty())
    }
}

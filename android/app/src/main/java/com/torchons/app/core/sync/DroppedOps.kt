package com.torchons.app.core.sync

import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.SyncAppliedOp

/**
 * The local completion rows to delete after a /sync answer: the INSERTs of `task_completions`
 * that the server acknowledged with `dropped = true`, i.e. without writing anything (a duplicate
 * completion, `completion_guard.py`). The optimistic row of such an op exists only on the phone:
 * left alone it would show a completion the household never had, until a refresh happens to
 * replace the scope. An older server omits the field, which reads as false: nothing is deleted.
 */
internal fun droppedCompletionRowIds(
    applied: List<SyncAppliedOp>,
    batch: List<PendingOperationEntity>,
): List<String> {
    val droppedOps = applied.filter { it.dropped }.map { it.clientOpId }.toSet()
    if (droppedOps.isEmpty()) return emptyList()
    return batch
        .filter { it.clientOpId in droppedOps && it.tableName == "task_completions" && it.operation == "INSERT" }
        .map { it.rowId }
}

package com.torchons.app.core.sync

import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.repository.CompletionRefreshGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D8 (2026-11-20 audit): an error while applying a server change was swallowed without a trace.
 * D1: the tasks epoch must move when the worker applies `tasks` changes.
 *
 * Paths by which "a failing change is reported, the rest still applies, the right epoch moves":
 *   (1) one change throws: reported with its change, the following ones still applied ..... COVERED
 *   (2) cancellation inside a change: propagates, not reported as an error ................ COVERED
 *   (3) a `tasks` change bumps the tasks epoch, not the completions one ................... COVERED
 *   (4) a failing apply still bumps the epoch (half-done change) ........................... COVERED
 * Not covered: the Log.w call itself (one lambda in SyncWorker, android.util.Log).
 */
class ApplyServerChangesTest {
    private fun change(table: String, row: String) =
        ChangeLogEntry(1, table, row, "INSERT", null, "2026-11-20T10:00:00Z")

    @Test
    fun `a failing change is reported and the rest of the batch still applies`() = runBlocking {
        val applied = mutableListOf<String>()
        val reported = mutableListOf<String>()

        applyServerChanges(
            CompletionRefreshGuard(),
            listOf(change("tasks", "a"), change("tasks", "bad"), change("tasks", "c")),
            onError = { c, e -> reported += "${c.rowId}:${e.message}" },
        ) {
            if (it.rowId == "bad") error("disk full")
            applied += it.rowId
        }

        assertEquals(listOf("a", "c"), applied)
        assertEquals(listOf("bad:disk full"), reported)
    }

    @Test
    fun `cancellation inside a change propagates and is not reported`() = runBlocking {
        val reported = mutableListOf<String>()
        try {
            applyServerChanges(
                CompletionRefreshGuard(), listOf(change("tasks", "a")),
                onError = { c, _ -> reported += c.rowId },
            ) { throw CancellationException("replaced") }
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            assertTrue(reported.isEmpty())
        }
    }

    @Test
    fun `a tasks change bumps the tasks epoch only, even when its apply fails`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val tasksBefore = guard.tasks.current()
        val completionsBefore = guard.currentEpoch()

        applyServerChanges(guard, listOf(change("tasks", "a")), onError = { _, _ -> }) { error("boom") }

        assertTrue(guard.tasks.current() != tasksBefore)
        assertEquals(completionsBefore, guard.currentEpoch())
    }

    @Test
    fun `changes of other tables leave the tasks epoch alone`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val tasksBefore = guard.tasks.current()

        applyServerChanges(guard, listOf(change("task_completions", "c"), change("task_delegations", "d"))) { }

        assertEquals(tasksBefore, guard.tasks.current())
    }
}

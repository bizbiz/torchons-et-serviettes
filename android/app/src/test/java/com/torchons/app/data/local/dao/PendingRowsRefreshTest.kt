package com.torchons.app.data.local.dao

import com.torchons.app.data.local.entities.CompletionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An online refresh of the completions cache leaves alone every row the
 * offline queue has not pushed yet.
 *
 * PATHS by which "a completion still in the queue survives a refresh" can
 * break, listed before the cases:
 *   (1) the server copy overwrites a pending row (`serverRowsToApply`) .. COVERED
 *   (2) the replace deletes a pending row (`deleteAllSynced`,
 *       `deleteSyncedByTask`) ........ not covered here: SQL on Room, no Room
 *       in JVM tests (app/build.gradle.kts). The subquery reads the same
 *       `pending_operations.rowId` set as (1).
 *   (3) a refresh path that bypasses replaceAll / replaceForTask ...... not
 *       covered: `grep -rn "completionDao\." android/app/src/main` lists the
 *       writers; on 2026-10-16 only wipeLocalCache (household switch, meant
 *       to clear everything) calls deleteAll.
 */
class PendingRowsRefreshTest {

    private fun row(id: String, by: String = "me") = CompletionEntity(
        id = id, taskId = "towels", completedBy = by,
        completedAt = "2026-10-15T12:00:00Z", pointsEarned = 1, notes = null,
    )

    @Test
    fun serverRowsWithoutPendingOpAreApplied() {
        val server = listOf(row("a"), row("b"))
        assertEquals(server, serverRowsToApply(server, emptySet()))
    }

    @Test
    fun pendingUpdateKeepsTheLocalValues() {
        // The server still has the row as it was before the local edit.
        val server = listOf(row("a", by = "me"), row("b"))
        assertEquals(listOf(row("b")), serverRowsToApply(server, setOf("a")))
    }

    @Test
    fun pendingDeleteIsNotBroughtBack() {
        val server = listOf(row("deleted-locally"))
        assertEquals(emptyList<CompletionEntity>(), serverRowsToApply(server, setOf("deleted-locally")))
    }
}

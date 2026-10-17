package com.torchons.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notices of ops the server refused for good survive a round trip through the
 * stored JSON, stay bounded, and an unreadable store means none.
 *
 * PATHS by which "a refused op is told to the user" can break, listed before the cases:
 *   (1) a notice written then read back (`appendRejectedOps` / `decodeRejectedOps`) .. COVERED
 *   (2) the store grows without bound (`MAX_REJECTED_NOTICES`) ......................... COVERED
 *   (3) a corrupt or empty store ...................................................... COVERED
 *   (4) SyncWorker drops a `rejected` op and records its notice .. not covered: Room + Hilt;
 *       the server side of the split (`rejected` vs `op_error`) is in
 *       backend/tests/test_sync_rejected.py.
 *   (5) the dialog shows then clears the notices ................. not covered: Compose UI.
 */
class RejectedOpsTest {

    private fun notice(title: String, at: Long) =
        RejectedSyncOp(PendingOpKind.COMPLETION_ADD, title, "not a member", at)

    @Test
    fun `a notice survives the stored json`() {
        val json = appendRejectedOps(null, listOf(notice("Poubelles", 1L)))
        assertEquals(listOf(notice("Poubelles", 1L)), decodeRejectedOps(json))
    }

    @Test
    fun `notices append and keep only the most recent`() {
        var json: String? = null
        repeat(MAX_REJECTED_NOTICES + 5) { i -> json = appendRejectedOps(json, listOf(notice("t$i", i.toLong()))) }
        val kept = decodeRejectedOps(json)
        assertEquals(MAX_REJECTED_NOTICES, kept.size)
        assertEquals("t${MAX_REJECTED_NOTICES + 4}", kept.last().taskTitle)
        assertEquals("t5", kept.first().taskTitle)
    }

    @Test
    fun `an unreadable store counts as none`() {
        assertTrue(decodeRejectedOps("{not json").isEmpty())
        assertTrue(decodeRejectedOps("").isEmpty())
        assertTrue(decodeRejectedOps("""[{"kind":"FROM_THE_FUTURE","rejectedAtMs":1}]""").isEmpty())
    }
}

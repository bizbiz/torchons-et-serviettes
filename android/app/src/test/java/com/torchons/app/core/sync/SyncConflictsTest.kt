package com.torchons.app.core.sync

import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.SyncConflict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8 (2026-11-20 audit): `out_of_scope` ops were deleted with no notice (an offline completion on
 * a task someone else deleted just disappeared) and conflict causes were shown raw.
 *
 * Paths by which "a dropped or delayed op is never silent, and never raw":
 *   (1) out_of_scope, household is the current one (row gone): op deleted AND a notice ... COVERED
 *   (2) out_of_scope, op of another household (left/switched on purpose): deleted, NO notice . COVERED
 *   (3) out_of_scope on an op queued before Room v14 (no foyerId): treated as current: notice . COVERED
 *   (4) rejected: notice (with the task title read BEFORE the discard), then discard ......... COVERED
 *   (5) any other reason: stays queued, translated cause stored (not the raw `reason`) ....... COVERED
 *   (6) a conflict whose op is not in the batch: handled without a notice, no crash ......... COVERED
 *   (7) no conflict: nothing written ......................................................... COVERED
 * Not covered: the stored sentence in the dialog (Compose); the real DAOs behind
 * EntryPointConflictStore (Hilt); "removed from the household by someone else" is
 * indistinguishable from a leave and stays silent when the op's household is not the current one.
 */
class SyncConflictsTest {
    private class FakeStore(val current: String? = "home-B") : ConflictStore {
        val log = mutableListOf<String>()
        val notices = mutableListOf<RejectedSyncOp>()
        val titles = mutableMapOf("t1" to "Vacuum")
        override suspend fun deleteOp(clientOpId: String) { log += "delete:$clientOpId" }
        override suspend fun incrementAttempt(clientOpId: String, error: String) { log += "retry:$clientOpId:$error" }
        override suspend fun taskTitle(taskId: String) = titles[taskId]
        override suspend fun addNotices(notices: List<RejectedSyncOp>) { this.notices += notices }
        override suspend fun discard(clientOpId: String) { log += "discard:$clientOpId" }
        override suspend fun currentFoyerId() = current
    }

    private fun op(id: String, foyerId: String?, table: String = "task_completions") = PendingOperationEntity(
        clientOpId = id, tableName = table, operation = "INSERT", rowId = "row-$id",
        payloadJson = """{"task_id":"t1"}""", createdAt = 1L, foyerId = foyerId,
    )

    private fun conflict(id: String, reason: String, detail: String? = null) =
        SyncConflict(clientOpId = id, table = "task_completions", rowId = "row-$id", reason = reason, detail = detail)

    private fun settle(store: FakeStore, batch: List<PendingOperationEntity>, vararg conflicts: SyncConflict) =
        runBlocking {
            settleConflicts(
                conflicts.toList(), batch, store,
                textOf = { reason, detail -> "T($reason|$detail)" }, nowMs = { 42L },
            )
        }

    @Test
    fun `an op out of scope in the current household is dropped and told`() {
        val store = FakeStore()
        settle(store, listOf(op("a", "home-B")), conflict("a", "out_of_scope"))

        assertEquals(listOf("delete:a"), store.log)
        assertEquals(
            listOf(RejectedSyncOp(PendingOpKind.COMPLETION_ADD, "Vacuum", "T(out_of_scope|null)", 42L)),
            store.notices,
        )
    }

    @Test
    fun `an op out of scope because its household was left is dropped silently`() {
        val store = FakeStore(current = "home-B")
        settle(store, listOf(op("a", "home-A")), conflict("a", "out_of_scope"))

        assertEquals(listOf("delete:a"), store.log)
        assertTrue(store.notices.isEmpty())
    }

    @Test
    fun `an old op without a household counts as the current one`() {
        val store = FakeStore()
        settle(store, listOf(op("a", null)), conflict("a", "out_of_scope"))

        assertEquals(1, store.notices.size)
    }

    @Test
    fun `a rejected op is told with its task title, then discarded`() {
        val store = FakeStore()
        settle(store, listOf(op("a", "home-B")), conflict("a", "rejected", "HTTPException: 422: too long"))

        assertEquals("T(rejected|HTTPException: 422: too long)", store.notices.single().reason)
        assertEquals("Vacuum", store.notices.single().taskTitle)
        assertEquals(listOf("discard:a"), store.log)
    }

    @Test
    fun `any other conflict stays queued with the translated cause, never the raw reason`() {
        val store = FakeStore()
        settle(
            store, listOf(op("a", "home-B"), op("b", "home-B")),
            conflict("a", "stale_update"), conflict("b", "op_error", "ValueError: boom"),
        )

        assertEquals(listOf("retry:a:T(stale_update|null)", "retry:b:T(op_error|ValueError: boom)"), store.log)
        assertTrue(store.notices.isEmpty())
    }

    @Test
    fun `a conflict for an op that is not in the batch makes no notice`() {
        val store = FakeStore()
        settle(store, emptyList(), conflict("ghost", "out_of_scope"), conflict("ghost2", "rejected"))

        assertEquals(listOf("delete:ghost", "discard:ghost2"), store.log)
        assertTrue(store.notices.isEmpty())
    }

    @Test
    fun `no conflict writes nothing`() {
        val store = FakeStore()
        settle(store, listOf(op("a", "home-B")))
        assertTrue(store.log.isEmpty() && store.notices.isEmpty())
    }
}

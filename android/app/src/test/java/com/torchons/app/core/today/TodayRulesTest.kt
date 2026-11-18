package com.torchons.app.core.today

import com.torchons.app.core.sync.delegationEntityFromChange
import com.torchons.app.core.sync.pendingOpKind
import com.torchons.app.core.sync.PendingOpKind
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.Delegation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * « À faire aujourd'hui » rules the app applies without network (docs/plans/a-faire-aujourdhui.md).
 * Alice hands, Bob and Carol receive (generic names: the repository is public).
 *
 * PATHS by which a member could see or do the wrong thing, listed before the cases:
 *   (1) the « ! » offers only members who see the task, never me (`handOffRecipients`) .. COVERED
 *   (2) what a tap does: hand at once, ask whom, cancel (author), info (others) ......... COVERED
 *   (3) the red stack holds what others handed to me, oldest first (`todayStackFor`) ... COVERED
 *   (4) suggestions lose what I handed and what I received (`excludedFromSuggestions`) . COVERED
 *   (5) a pulled change_log entry is cached (INSERT) or dropped (DELETE / UPDATE) ...... COVERED
 *   (6) the sync dialog names the two ops ................................................ COVERED
 *   non couvert : the cache read hiding a delegation once its task is completed on or after
 *                 its day (DelegationDao.observeActive, SQL): Room needs an instrumented test.
 */
class TodayRulesTest {
    private val alice = "a"
    private val bob = "b"
    private val carol = "c"
    private val members = listOf(alice, bob, carol)

    private fun delegation(task: String, author: String, to: List<String>, at: String = "2026-11-18T08:00:00Z") =
        Delegation(id = "d-$task", taskId = task, authorId = author, recipientIds = to, createdAt = at)

    @Test
    fun recipients_are_the_members_who_see_the_task_minus_me() {
        assertEquals(listOf(bob), handOffRecipients(listOf(alice, bob), members, alice))
        assertEquals(listOf(bob, carol), handOffRecipients(emptyList(), members, alice))
        assertEquals(emptyList<String>(), handOffRecipients(listOf(alice), members, alice))
    }

    @Test
    fun a_tap_hands_asks_cancels_or_informs() {
        assertEquals(TodayTap.HandTo(listOf(bob)), todayTap(null, listOf(bob), alice))
        assertEquals(TodayTap.Pick(listOf(bob, carol)), todayTap(null, listOf(bob, carol), alice))
        assertNull(todayTap(null, emptyList(), alice))
        val mine = delegation("t", alice, listOf(bob))
        assertEquals(TodayTap.Cancel(mine), todayTap(mine, listOf(bob), alice))
        assertEquals(TodayTap.Info(mine), todayTap(mine, listOf(alice, carol), bob))
    }

    @Test
    fun the_stack_holds_what_others_handed_me_oldest_first() {
        val late = delegation("t2", alice, listOf(bob, carol), at = "2026-11-18T10:00:00Z")
        val early = delegation("t1", carol, listOf(bob), at = "2026-11-17T10:00:00Z")
        val toCarol = delegation("t3", alice, listOf(carol))
        assertEquals(listOf(early, late), todayStackFor(listOf(late, toCarol, early), bob))
        assertEquals(emptyList<Delegation>(), todayStackFor(listOf(late, toCarol), alice))
    }

    @Test
    fun suggestions_lose_what_i_handed_and_what_i_received() {
        val all = listOf(delegation("t1", alice, listOf(bob)), delegation("t2", carol, listOf(alice)),
            delegation("t3", bob, listOf(carol)))
        assertEquals(setOf("t1", "t2"), excludedFromSuggestions(all, alice))
    }

    @Test
    fun names_join_with_an_ampersand() {
        assertEquals("Alice", joinNames(listOf("Alice")))
        assertEquals("Alice, Bob & Carol", joinNames(listOf("Alice", "Bob", "Carol")))
    }

    @Test
    fun a_pulled_insert_is_cached_and_its_end_drops_it() {
        val data = mapOf(
            "task_id" to "t", "author_id" to alice, "recipient_ids" to listOf(bob),
            "created_at" to "2026-11-18T08:00:00+00:00",
        )
        val insert = ChangeLogEntry(1, "task_delegations", "d", "INSERT", data, "2026-11-18T08:00:01Z")
        val cached = delegationEntityFromChange(insert)!!
        assertEquals(listOf(bob), cached.recipientIds)
        assertEquals(alice, cached.authorId)
        val settled = insert.copy(operation = "UPDATE", data = mapOf("task_id" to "t", "settled_at" to "x"))
        assertNull(delegationEntityFromChange(settled))
        assertNull(delegationEntityFromChange(insert.copy(operation = "DELETE", data = null)))
    }

    @Test
    fun the_sync_dialog_names_both_ops() {
        assertEquals(PendingOpKind.DELEGATION_ADD, pendingOpKind("task_delegations", "INSERT"))
        assertEquals(PendingOpKind.DELEGATION_CANCEL, pendingOpKind("task_delegations", "DELETE"))
    }
}

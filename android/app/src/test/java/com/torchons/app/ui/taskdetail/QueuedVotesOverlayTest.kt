package com.torchons.app.ui.taskdetail

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.core.sync.AFFINITY_VOTES_TABLE
import com.torchons.app.core.sync.EFFORT_VOTES_TABLE
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.repository.QUEUED_VOTE_TABLES
import com.torchons.app.data.repository.QueuedVotes
import com.torchons.app.data.repository.queuedVotesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator finding of 2026-11-20 (item 1): an effort and an affinity vote saved offline sat in the
 * queue ("Vote d'effort", "Vote d'affinité") but the task detail went on saying "Tu n'as pas encore
 * voté" and its "Effort & Affinité" block had no member row, until the sync. The screen's votes
 * are the server's last answer (a live fetch, no cache) with my queued votes laid over them.
 *
 * Paths by which "what I just saved offline is what I see":
 *   (1) a vote queued on a REAL Room queue reaches the screen's state, the latest of two wins ... COVERED
 *   (2) the fetch failed or never ran (server votes empty): my row still appears ................. COVERED
 *   (3) the fetch worked: my row is replaced in place, the others stay, the average follows ....... COVERED
 *   (4) the banner's inputs (`effortVotes.myPoints`, my affinity) are no longer missing .......... COVERED
 *   (5) nothing queued (online, or sent): the server's answer is shown untouched ................. COVERED
 *   (6) another task's votes, or a prompt answer, never leak into this task ...................... COVERED
 * Not covered: the ViewModel's collect / refetch-on-drain wiring and the Compose screen (Hilt).
 */
@RunWith(AndroidJUnit4::class)
class QueuedVotesOverlayTest {
    private lateinit var db: AppDatabase
    private val me = "u-me"

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private fun overlay(server: ServerVotes, queued: QueuedVotes) =
        overlayQueuedVotes(server, queued, taskId = "t1", me = me, myName = "Quentin")

    private suspend fun queuedFromRoom(taskId: String = "t1"): QueuedVotes =
        queuedVotesOf(db.pendingOperationDao().observeForRow(QUEUED_VOTE_TABLES, taskId).first())

    @Test
    fun a_vote_queued_in_room_reaches_the_screen_and_the_latest_wins() = runBlocking {
        val dao = db.pendingOperationDao()
        dao.insert(pendingOp("a1", AFFINITY_VOTES_TABLE, "VOTE", "t1", payloadJson = """{"score":3}""", createdAt = 1))
        dao.insert(pendingOp("a2", AFFINITY_VOTES_TABLE, "VOTE", "t1", payloadJson = """{"score":9}""", createdAt = 2))
        dao.insert(pendingOp("e1", EFFORT_VOTES_TABLE, "VOTE", "t1", payloadJson = """{"points":9}""", createdAt = 3))

        val shown = overlay(ServerVotes(), queuedFromRoom())

        val row = shown.ratings.single()
        assertEquals(me, row.userId)
        assertEquals("Quentin", row.displayName)
        assertEquals(9, row.affinity)
        assertEquals(45, row.effortTimeMin)
    }

    @Test
    fun the_section_is_not_blank_when_the_fetch_never_answered() {
        val shown = overlay(ServerVotes(), QueuedVotes(affinity = 9, effortPoints = 9))

        assertEquals(1, shown.ratings.size)
        assertEquals(45, shown.avgEffortTimeMin)
        assertEquals(9, shown.scorePoints)
    }

    @Test
    fun my_row_is_replaced_in_place_and_the_average_follows() {
        val other = TaskRating("u-lo", "Lo", affinity = 4, effortTimeMin = 15)
        val old = TaskRating(me, "Quentin", affinity = 2, effortTimeMin = 5)
        val server = ServerVotes(listOf(other, old), avgEffortTimeMin = 10, scorePoints = 2)

        val shown = overlay(server, QueuedVotes(affinity = 9, effortPoints = 9))

        assertEquals(listOf(other, TaskRating(me, "Quentin", 9, 45)), shown.ratings)
        assertEquals(30, shown.avgEffortTimeMin)
        assertEquals(6, shown.scorePoints)
    }

    @Test
    fun an_affinity_alone_keeps_the_server_effort_and_average() {
        val old = TaskRating(me, "Quentin", affinity = 2, effortTimeMin = 20)
        val server = ServerVotes(listOf(old), avgEffortTimeMin = 20, scorePoints = 4)

        val shown = overlay(server, QueuedVotes(affinity = 7))

        assertEquals(TaskRating(me, "Quentin", 7, 20), shown.ratings.single())
        assertEquals(20, shown.avgEffortTimeMin)
        assertEquals(4, shown.scorePoints)
    }

    @Test
    fun the_banner_inputs_are_no_longer_missing() {
        val shown = overlay(ServerVotes(), QueuedVotes(affinity = 9, effortPoints = 9))

        assertEquals(9, shown.effortVotes?.myPoints)
        assertEquals(1, shown.effortVotes?.votesCount)
        assertEquals(9, shown.ratings.firstOrNull { it.userId == me }?.affinity)
    }

    @Test
    fun a_server_effort_state_keeps_its_consensus_and_counts_my_new_vote_once() {
        val serverEffort = EffortVotesState("t1", consensusPoints = 4, votesCount = 2, myPoints = null)
        val server = ServerVotes(effortVotes = serverEffort)

        val first = overlay(server, QueuedVotes(effortPoints = 9)).effortVotes
        val revote = overlay(server.copy(effortVotes = first), QueuedVotes(effortPoints = 6)).effortVotes

        assertEquals(3, first?.votesCount)
        assertEquals(4, first?.consensusPoints)
        assertEquals(3, revote?.votesCount)
        assertEquals(6, revote?.myPoints)
    }

    @Test
    fun nothing_queued_shows_the_server_untouched() {
        val server = ServerVotes(listOf(TaskRating("u-lo", "Lo", 4, 15)), avgEffortTimeMin = 15, scorePoints = 3)

        assertEquals(server, overlay(server, QueuedVotes()))
        assertTrue(QueuedVotes().isEmpty)
    }

    @Test
    fun another_task_or_a_prompt_answer_never_leaks_in() = runBlocking {
        val dao = db.pendingOperationDao()
        dao.insert(pendingOp("o1", AFFINITY_VOTES_TABLE, "VOTE", "t2", payloadJson = """{"score":9}"""))
        val answer = """{"resolution":"DISMISSED"}"""
        dao.insert(pendingOp("o2", "prompt_resolutions", "RESOLVE", "t1", payloadJson = answer))

        val queued = queuedFromRoom("t1")

        assertTrue(queued.isEmpty)
        assertNull(queued.affinity)
    }
}

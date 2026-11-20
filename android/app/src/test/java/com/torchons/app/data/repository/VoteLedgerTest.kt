package com.torchons.app.data.repository

import com.torchons.app.core.sync.AFFINITY_VOTES_TABLE
import com.torchons.app.core.sync.EFFORT_VOTES_TABLE
import com.torchons.app.core.sync.PROMPT_RESOLUTIONS_TABLE
import com.torchons.app.core.sync.PendingOpKind
import com.torchons.app.core.sync.pendingOpKind
import com.torchons.app.core.sync.taskIdOf
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.PromptResolveRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D3 / D6 (2026-11-20 audit): effort votes, affinity votes and the answer to a prompt are
 * "set my value" writes with a REST route (`PUT /tasks/{id}/effort`, `/affinity`, `POST
 * /prompts/{id}/resolve`, idempotent: read in backend/app/api/gamification.py and prompts.py). When
 * the send fails they are QUEUED, and the worker replays them over REST (`RestOpsPushTest`).
 *
 * Paths by which "a vote that could not be sent is kept, and shown for what it is":
 *   (1) each queue write lands as ONE op of its own table, keyed by task (votes) or prompt
 *       (answer), with its payload, in the household of the moment, one sync requested ...... COVERED
 *   (2) a prompt answer keeps its payload (a re-vote's days_per_month) and its resolution ..... COVERED
 *   (3) the sync dialog names each kind, and finds the task a vote is about .................. COVERED
 *   (4) the rules of WHEN to queue (offline, 5xx, 4xx, task not on the server yet) are
 *       `SendOrQueueTest`'s .................................................................. other file
 * Not covered: `VoteRepository`'s wiring (Hilt: three lambdas into `sendOrQueue`, read in the diff).
 */
class VoteLedgerTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncs = AtomicInteger()
    private val db = FakeLocalDb()
    private val ledger = VoteLedger(LocalMutation(db, appScope, { syncs.incrementAndGet() }, { "home" }))

    @Test
    fun `an effort vote lands as one op keyed by its task`() = runBlocking {
        ledger.enqueueEffort("t1", 7)

        val op = db.ops.single()
        assertEquals(EFFORT_VOTES_TABLE, op.tableName)
        assertEquals("t1", op.rowId)
        assertEquals("""{"points":7}""", op.payloadJson)
        assertEquals("home", op.foyerId)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `an affinity vote lands as one op keyed by its task`() = runBlocking {
        ledger.enqueueAffinity("t1", 4)

        val op = db.ops.single()
        assertEquals(AFFINITY_VOTES_TABLE, op.tableName)
        assertEquals("""{"score":4}""", op.payloadJson)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `a prompt answer keeps its resolution and its payload`() = runBlocking {
        val answer = PromptResolveRequest.frequencyAnswer(FrequencyRequest(everyNDays = 7))

        ledger.enqueueResolution("prompt-1", answer)

        val op = db.ops.single()
        assertEquals(PROMPT_RESOLUTIONS_TABLE, op.tableName)
        assertEquals("prompt-1", op.rowId)
        assertEquals(true, op.payloadJson.contains(""""resolution":"ANSWERED""""))
        assertEquals(true, op.payloadJson.contains(""""days_per_month""""))
        assertEquals(1, syncs.get())
    }

    @Test
    fun `the sync dialog names each kind and finds the task of a vote`() = runBlocking {
        ledger.enqueueEffort("t1", 7)
        ledger.enqueueAffinity("t1", 4)
        ledger.enqueueResolution("prompt-1", PromptResolveRequest("DISMISSED"))
        val (effort, affinity, answer) = db.ops

        assertEquals(PendingOpKind.EFFORT_VOTE, pendingOpKind(effort.tableName, effort.operation))
        assertEquals(PendingOpKind.AFFINITY_VOTE, pendingOpKind(affinity.tableName, affinity.operation))
        assertEquals(PendingOpKind.PROMPT_ANSWER, pendingOpKind(answer.tableName, answer.operation))
        assertEquals("t1", taskIdOf(effort))
        assertEquals("t1", taskIdOf(affinity))
        assertNull("an answer is about a prompt, not a task", taskIdOf(answer))
    }
}

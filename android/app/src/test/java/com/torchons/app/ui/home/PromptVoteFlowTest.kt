package com.torchons.app.ui.home

import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UserPrompt
import com.torchons.app.ui.common.UserError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 (2026-11-20 audit), « Tâche souvent en retard → Revoter » and the vote prompts: offline, the
 * frequency vote kept its dialog open (the effort dialog's GET failed in silence), each extra tap
 * queued another vote; online, a failed `resolvePrompt` was swallowed, so the answered prompt came
 * back.
 *
 * Paths by which "once a vote is accepted or queued the flow moves on, visibly":
 *   (1) chain, frequency vote accepted, effort state loads: effort dialog replaces it ....... COVERED
 *   (2) chain, frequency vote queued (offline), effort state cannot load: the chain ENDS with
 *       the « réseau nécessaire pour l'étape suivante » message, no dialog left, the prompt
 *       answered (queued) so it does not come back ....................................... COVERED
 *   (3) a second tap while a vote is in flight: ignored (one vote, not two) ............... COVERED
 *   (4) a refused vote (permanent): error shown, dialog and prompt stay ..................... COVERED
 *   (5) effort vote accepted: prompt answered, everything closed, refresh asked ............. COVERED
 *   (6) the answer cannot be sent (refused): the UI still closes (nothing to retry) ......... COVERED
 *   (7) opening a vote that needs the network, offline: told, modal closed for a plain vote
 *       prompt (composition would re-dispatch it for ever) / kept in the chain .............. COVERED
 *   (8) cancel inside the chain goes back to the bump dialog, nothing answered .............. COVERED
 *   (9) a task missing from the cache: told, plain prompt dismissed .......................... COVERED
 * Not covered: the Compose dialogs and the real repositories (Hilt, Room): the port is a fake;
 * the sending and queueing rules are `SendOrQueueTest`'s.
 */
class PromptVoteFlowTest {
    private class FakePort : PromptVotePort {
        val calls = mutableListOf<String>()
        var effortStateResult: Result<EffortVotesState> = Result.success(EffortVotesState("t1"))
        var frequencyStateResult: Result<FrequencyVotesState> = Result.success(FrequencyVotesState("t1"))
        var voteFrequencyResult: Result<Unit> = Result.success(Unit)
        var voteEffortResult: Result<Unit> = Result.success(Unit)
        var resolveResult: Result<Unit> = Result.success(Unit)

        /** When set, the frequency vote waits for it: a request in flight. */
        var voteGate: CompletableDeferred<Unit>? = null
        val resolutions = mutableListOf<PromptResolveRequest>()

        override suspend fun frequencyState(taskId: String): Result<FrequencyVotesState> =
            frequencyStateResult.also { calls += "frequencyState" }

        override suspend fun effortState(taskId: String): Result<EffortVotesState> =
            effortStateResult.also { calls += "effortState" }

        override suspend fun voteFrequency(taskId: String, request: FrequencyRequest): Result<Unit> {
            calls += "voteFrequency"
            voteGate?.await()
            return voteFrequencyResult
        }

        override suspend fun voteEffort(taskId: String, points: Int): Result<Unit> =
            voteEffortResult.also { calls += "voteEffort" }

        override suspend fun resolve(promptId: String, request: PromptResolveRequest): Result<Unit> {
            calls += "resolve"
            resolutions += request
            return resolveResult
        }
    }

    private val bumpPrompt = UserPrompt(
        id = "p1", type = "BUMP_EFFORT", priority = 1, createdAt = "2026-11-20T08:00:00Z",
        context = mapOf("task_id" to "t1", "bonus_ratio" to 0.6, "window_days" to 30),
    )
    private val votePrompt = bumpPrompt.copy(type = "VOTE_EFFORT")
    private val tasks = listOf(Task(id = "t1", title = "Vacuum"))

    private class Rig(prompt: UserPrompt, val port: FakePort, tasks: List<Task>) {
        val state = MutableStateFlow(HomeUiState(currentPrompt = prompt, showPromptModal = true))
        var refreshes = 0
        val flow = PromptVoteFlow(
            // Unconfined: a launched gesture runs to its first suspension at once, so the tests are
            // deterministic, and a gate (CompletableDeferred) models "a request in flight".
            scope = CoroutineScope(Dispatchers.Unconfined),
            uiState = state,
            port = port,
            activeTasks = { tasks },
            refresh = { refreshes++ },
            errorOf = { UserError("translated: ${it.javaClass.simpleName}") },
            texts = PromptVoteTexts(
                networkNeededToVote = "NEEDS NETWORK TO VOTE",
                effortStepNeedsNetwork = "EFFORT STEP NEEDS NETWORK",
                taskUnavailable = "TASK UNAVAILABLE",
            ),
        )
    }

    private fun rig(prompt: UserPrompt = bumpPrompt, taskList: List<Task> = tasks) =
        Rig(prompt, FakePort(), taskList)

    /** The chain up to the open frequency dialog. */
    private fun Rig.inFrequencyStep() {
        flow.startBumpRevote()
        assertNotNull("the frequency dialog opened", state.value.pendingFrequencyVoteContext)
    }

    private val request = FrequencyRequest(everyNDays = 7)

    @Test
    fun `chain - an accepted frequency vote opens the effort step`() {
        val r = rig()
        r.inFrequencyStep()

        r.flow.submitPromptFrequencyVote(request)

        assertNull(r.state.value.pendingFrequencyVoteContext)
        assertNotNull(r.state.value.pendingEffortVoteContext)
        assertNotNull("the prompt stays current until the end of the chain", r.state.value.currentPrompt)
        assertEquals(0, r.port.resolutions.size)
    }

    @Test
    fun `chain - offline after the frequency vote the chain ends with the network message`() {
        val r = rig()
        r.inFrequencyStep()
        r.port.effortStateResult = Result.failure(IOException("no route"))

        r.flow.submitPromptFrequencyVote(request)

        val s = r.state.value
        assertNull("no dialog left hanging", s.pendingFrequencyVoteContext)
        assertNull(s.pendingEffortVoteContext)
        assertNull("the prompt is over", s.currentPrompt)
        assertEquals("EFFORT STEP NEEDS NETWORK", s.error?.message)
        assertEquals(listOf("ANSWERED"), r.port.resolutions.map { it.resolution })
        assertEquals(mapOf("action" to "revote"), r.port.resolutions.single().payload)
        assertEquals(1, r.refreshes)
        assertEquals("the vote was sent once", 1, r.port.calls.count { it == "voteFrequency" })
    }

    @Test
    fun `a second tap while the vote is in flight is ignored`() {
        val r = rig()
        r.inFrequencyStep()
        val gate = CompletableDeferred<Unit>()
        r.port.voteGate = gate

        r.flow.submitPromptFrequencyVote(request)
        r.flow.submitPromptFrequencyVote(request)
        r.flow.submitPromptFrequencyVote(request)
        gate.complete(Unit)

        assertEquals(1, r.port.calls.count { it == "voteFrequency" })
        assertNotNull("and the chain went on", r.state.value.pendingEffortVoteContext)
    }

    @Test
    fun `a refused vote is told and the dialog stays`() {
        val r = rig()
        r.inFrequencyStep()
        r.port.voteFrequencyResult = Result.failure(ApiHttpException(409))

        r.flow.submitPromptFrequencyVote(request)

        assertEquals("translated: ApiHttpException", r.state.value.error?.message)
        assertNotNull(r.state.value.pendingFrequencyVoteContext)
        assertNotNull(r.state.value.currentPrompt)
        assertTrue(r.port.resolutions.isEmpty())
        assertEquals("not asked for the effort step", 0, r.port.calls.count { it == "effortState" })
    }

    @Test
    fun `an accepted effort vote answers the prompt and closes everything`() {
        val r = rig(votePrompt)
        r.flow.openEffortVoteFromPrompt("t1", "Alice")
        assertNotNull(r.state.value.pendingEffortVoteContext)

        r.flow.submitPromptEffortVote(7)

        val s = r.state.value
        assertNull(s.pendingEffortVoteContext)
        assertNull(s.currentPrompt)
        assertEquals(false, s.showPromptModal)
        assertEquals(PromptResolveRequest.effortAnswer(7), r.port.resolutions.single())
        assertEquals(1, r.refreshes)
    }

    @Test
    fun `a refused effort vote is told and the dialog stays`() {
        val r = rig(votePrompt)
        r.flow.openEffortVoteFromPrompt("t1", "Alice")
        r.port.voteEffortResult = Result.failure(ApiHttpException(422))

        r.flow.submitPromptEffortVote(7)

        assertEquals("translated: ApiHttpException", r.state.value.error?.message)
        assertNotNull("the dialog is still there to retry or cancel", r.state.value.pendingEffortVoteContext)
        assertNotNull(r.state.value.currentPrompt)
        assertTrue("the prompt is not answered as if it had worked", r.port.resolutions.isEmpty())
        assertEquals(0, r.refreshes)
    }

    @Test
    fun `an effort vote queued offline closes the dialog like a sent one, and its answer is queued`() {
        val r = rig(votePrompt)
        r.flow.openEffortVoteFromPrompt("t1", "Alice")
        // The port reports a queued vote as a success (SendOrQueue): offline looks like online here.

        r.flow.submitPromptEffortVote(7)
        r.flow.submitPromptEffortVote(7)

        assertNull(r.state.value.pendingEffortVoteContext)
        assertNull(r.state.value.error)
        assertEquals("one vote, however many taps", 1, r.port.calls.count { it == "voteEffort" })
        assertEquals(1, r.port.resolutions.size)
    }

    @Test
    fun `an answer that cannot be sent does not keep the dialog open`() {
        val r = rig(votePrompt)
        r.flow.openEffortVoteFromPrompt("t1", "Alice")
        r.port.resolveResult = Result.failure(ApiHttpException(404))

        r.flow.submitPromptEffortVote(7)

        assertNull(r.state.value.pendingEffortVoteContext)
        assertNull(r.state.value.currentPrompt)
        assertNull("nothing for the user to fix", r.state.value.error)
    }

    @Test
    fun `opening a vote offline says so and closes a plain vote prompt`() {
        val r = rig(votePrompt)
        r.port.effortStateResult = Result.failure(IOException("no route"))

        r.flow.openEffortVoteFromPrompt("t1", "Alice")

        assertEquals("NEEDS NETWORK TO VOTE", r.state.value.error?.message)
        assertEquals("the modal must not re-dispatch the vote for ever", false, r.state.value.showPromptModal)
        assertNull(r.state.value.pendingEffortVoteContext)
    }

    @Test
    fun `opening the chain offline says so and keeps the bump dialog`() {
        val r = rig()
        r.port.frequencyStateResult = Result.failure(IOException("no route"))

        r.flow.startBumpRevote()

        assertEquals("NEEDS NETWORK TO VOTE", r.state.value.error?.message)
        assertEquals(true, r.state.value.showPromptModal)
        assertNotNull(r.state.value.currentPrompt)
        assertNull(r.state.value.pendingFrequencyVoteContext)
    }

    @Test
    fun `an open that fails for another reason shows the translated error`() {
        val r = rig(votePrompt)
        r.port.effortStateResult = Result.failure(ApiHttpException(500))

        r.flow.openEffortVoteFromPrompt("t1", "Alice")

        assertEquals("translated: ApiHttpException", r.state.value.error?.message)
    }

    @Test
    fun `cancel inside the chain goes back to the bump dialog and answers nothing`() {
        val r = rig()
        r.inFrequencyStep()

        r.flow.dismissPromptFrequencyVote()

        val s = r.state.value
        assertNull(s.pendingFrequencyVoteContext)
        assertEquals(true, s.showPromptModal)
        assertNotNull(s.currentPrompt)
        assertTrue(r.port.resolutions.isEmpty())
    }

    @Test
    fun `a task missing from the cache is told and a plain prompt is dismissed`() {
        val r = rig(votePrompt, taskList = emptyList())

        r.flow.openEffortVoteFromPrompt("t1", "Alice")

        assertEquals("TASK UNAVAILABLE", r.state.value.error?.message)
        assertNull(r.state.value.currentPrompt)
        assertEquals(listOf("DISMISSED"), r.port.resolutions.map { it.resolution })
    }

    @Test
    fun `closing a plain vote dialog dismisses the prompt, queued like any answer`() {
        val r = rig(votePrompt)
        r.flow.openEffortVoteFromPrompt("t1", "Alice")

        r.flow.dismissPromptEffortVote()

        assertEquals(listOf("DISMISSED"), r.port.resolutions.map { it.resolution })
        assertNull(r.state.value.currentPrompt)
        assertEquals(1, r.refreshes)
    }
}

package com.torchons.app.ui.home

import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.model.Task
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.isNetworkFailure
import com.torchons.app.ui.common.voteHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the vote dialogs of a prompt need from the data layer; the repositories in the app, a fake in tests. */
internal interface PromptVotePort {
    /** The state the dialog shows; a failure when the server cannot be reached or refuses. */
    suspend fun frequencyState(taskId: String): Result<FrequencyVotesState>

    suspend fun effortState(taskId: String): Result<EffortVotesState>

    /** Online-first, queued on a network failure; a failure is a permanent refusal. */
    suspend fun voteFrequency(taskId: String, request: FrequencyRequest): Result<Unit>

    suspend fun voteEffort(taskId: String, points: Int): Result<Unit>

    /** Answers the prompt, or queues the answer; a failure is a permanent refusal (prompt gone). */
    suspend fun resolve(promptId: String, request: PromptResolveRequest): Result<Unit>
}

/** The sentences the flow itself shows (the rest comes from [UserError]s). */
internal class PromptVoteTexts(
    /** Opening a vote needs its current state from the server, and there is no network. */
    val networkNeededToVote: String,
    /** The effort step of the « souvent en retard » chain cannot load: said, not hung. */
    val effortStepNeedsNetwork: String,
    val taskUnavailable: String,
)

// ---- Dialog state transitions (pure, tested in PromptVoteStepsTest) ----

/** The frequency dialog opens on top of the prompt: the queue modal steps aside. */
internal fun HomeUiState.withFrequencyDialog(ctx: PromptVoteContext) =
    copy(pendingFrequencyVoteContext = ctx, showPromptModal = false)

/**
 * The effort dialog replaces the frequency one. In the « souvent en retard » chain no instant
 * shows neither dialog, so the queue never fetches another prompt in between.
 */
internal fun HomeUiState.withEffortDialog(ctx: PromptVoteContext) =
    copy(pendingEffortVoteContext = ctx, pendingFrequencyVoteContext = null, showPromptModal = false)

/** The prompt is over (answered, dismissed or ended): no dialog, no modal, no current prompt. */
internal fun HomeUiState.promptDone() = copy(
    pendingFrequencyVoteContext = null,
    pendingEffortVoteContext = null,
    currentPrompt = null,
    showPromptModal = false,
)

/** Cancel inside the chain: back to « Tâche souvent en retard », still open. */
internal fun HomeUiState.backToBumpDialog() = copy(
    pendingFrequencyVoteContext = null,
    pendingEffortVoteContext = null,
    showPromptModal = true,
)

/**
 * A vote dialog could not be opened. Said on screen. In the chain ([keepModal]) the bump modal
 * stays, so the user can retry or pick « Plus tard ». For a plain vote prompt the modal is closed:
 * PromptModal dispatches those prompts from composition, and an error that changes the state would
 * re-dispatch it for ever.
 */
internal fun HomeUiState.openFailed(error: UserError, keepModal: Boolean) =
    copy(error = error, showPromptModal = showPromptModal && keepModal)

/**
 * The chain's effort step cannot load: the prompt ends here, with [message] (the frequency vote
 * is already saved or queued; nothing hangs and nothing is asked twice).
 */
internal fun HomeUiState.chainEndedWithoutEffort(message: UserError) = promptDone().copy(error = message)

/**
 * The vote dialogs of the prompt queue (VOTE_FREQUENCY / VOTE_EFFORT, and the « souvent en retard »
 * chain frequency then effort), split out of HomePromptActions. Reads and writes the ViewModel's
 * [uiState]; [refresh] asks the ViewModel to chain to the next prompt.
 *
 * Contract (D6, 2026-11-20 audit): once a vote is accepted or queued the flow MOVES ON, visibly;
 * a second tap while one is in flight is ignored (offline, each tap used to queue another vote);
 * every failure to open or send is said on screen; the answer to the prompt goes through
 * [PromptVotePort.resolve], which queues it, so an answered prompt does not come back.
 */
internal class PromptVoteFlow(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val port: PromptVotePort,
    private val activeTasks: () -> List<Task>,
    private val refresh: () -> Unit,
    private val errorOf: (Throwable) -> UserError,
    private val texts: PromptVoteTexts,
) {
    private var busy = false

    private fun say(error: UserError) {
        uiState.value = uiState.value.copy(error = error)
    }

    /** Runs [block] unless another gesture of the flow is in flight. */
    private fun exclusive(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    /** The user answers the current prompt (ANSWERED with a payload, or DISMISSED). */
    fun resolveCurrentPrompt(resolution: String, payload: Map<String, Any?>? = null) {
        val prompt = uiState.value.currentPrompt ?: return
        val wasPresencePrompt = prompt.type == "RESOLVE_PRESENCE"
        val wasDismissed = resolution == "DISMISSED"
        exclusive {
            port.resolve(prompt.id, PromptResolveRequest(resolution, payload))
            uiState.value = uiState.value.copy(
                currentPrompt = null,
                showPromptModal = false,
                // A dismissed RESOLVE_PRESENCE (« Plus tard ») keeps the banner: tasks stay frozen.
                hasUnresolvedPresenceDays = wasPresencePrompt && wasDismissed,
            )
            refresh()
        }
    }

    /**
     * « Tâche souvent en retard » → Revoter: frequency vote, then effort vote, the BUMP_EFFORT
     * prompt staying current (so unresolved) until the end.
     */
    fun startBumpRevote() {
        val ctx = uiState.value.currentPrompt?.context ?: return
        val taskId = ctx["task_id"]?.toString() ?: return
        val bump = BumpRevote(
            latePercent = (((ctx["bonus_ratio"] as? Number)?.toFloat() ?: 0f) * 100).toInt(),
            windowDays = (ctx["window_days"] as? Number)?.toInt() ?: 0,
        )
        openFrequencyVoteFromPrompt(taskId, requesterName = "", bump = bump)
    }

    fun openFrequencyVoteFromPrompt(taskId: String, requesterName: String, bump: BumpRevote? = null) {
        val prompt = uiState.value.currentPrompt ?: return
        exclusive { openFrequency(prompt.id, taskId, requesterName, bump) }
    }

    fun openEffortVoteFromPrompt(taskId: String, requesterName: String, bump: BumpRevote? = null) {
        val prompt = uiState.value.currentPrompt ?: return
        exclusive { openEffort(prompt.id, taskId, requesterName, bump) }
    }

    private suspend fun openFrequency(promptId: String, taskId: String, requester: String, bump: BumpRevote?) {
        val state = port.frequencyState(taskId).getOrElse { return couldNotOpen(it, bump, promptId) }
        val task = activeTasks().firstOrNull { it.id == taskId } ?: return taskGone(bump, promptId)
        val header = voteHeader(task)
        val ctx = PromptVoteContext(promptId, taskId, header, requester, frequencyState = state, bump = bump)
        uiState.value = uiState.value.withFrequencyDialog(ctx)
    }

    private suspend fun openEffort(promptId: String, taskId: String, requester: String, bump: BumpRevote?) {
        val state = port.effortState(taskId).getOrElse { return couldNotOpen(it, bump, promptId) }
        val task = activeTasks().firstOrNull { it.id == taskId } ?: return taskGone(bump, promptId)
        val header = voteHeader(task)
        val ctx = PromptVoteContext(promptId, taskId, header, requester, effortState = state, bump = bump)
        uiState.value = uiState.value.withEffortDialog(ctx)
    }

    /** [failure] hit while opening a dialog. Inside the chain after the frequency vote: it ends the chain. */
    private suspend fun couldNotOpen(failure: Throwable, bump: BumpRevote?, promptId: String) {
        val chainOnEffortStep = bump != null && uiState.value.pendingFrequencyVoteContext != null
        if (chainOnEffortStep) {
            port.resolve(promptId, PromptResolveRequest("ANSWERED", mapOf("action" to "revote")))
            uiState.value = uiState.value.chainEndedWithoutEffort(UserError(texts.effortStepNeedsNetwork))
            refresh()
            return
        }
        val error = if (failure.isNetworkFailure()) UserError(texts.networkNeededToVote) else errorOf(failure)
        uiState.value = uiState.value.openFailed(error, keepModal = bump != null)
    }

    /** The task of the prompt is not in the cache any more: said, and the prompt closed so it cannot loop. */
    private suspend fun taskGone(bump: BumpRevote?, promptId: String) {
        uiState.value = uiState.value.openFailed(UserError(texts.taskUnavailable), keepModal = bump != null)
        if (bump == null) {
            port.resolve(promptId, PromptResolveRequest("DISMISSED"))
            uiState.value = uiState.value.promptDone()
            refresh()
        }
    }

    fun submitPromptFrequencyVote(request: FrequencyRequest) {
        val ctx = uiState.value.pendingFrequencyVoteContext ?: return
        exclusive {
            // A refused vote (task gone, locked...) is told and the dialog stays open; a network
            // failure was queued by the port and counts as sent.
            val failure = port.voteFrequency(ctx.taskId, request).exceptionOrNull()
            if (failure != null) return@exclusive say(errorOf(failure))
            if (ctx.bump != null) {
                // Step 1 of 2: the prompt stays open, the effort vote follows at once.
                openEffort(ctx.promptId, ctx.taskId, ctx.requesterName, ctx.bump)
            } else {
                finish(ctx.promptId, PromptResolveRequest.frequencyAnswer(request))
            }
        }
    }

    fun submitPromptEffortVote(points: Int) {
        val ctx = uiState.value.pendingEffortVoteContext ?: return
        exclusive {
            val failure = port.voteEffort(ctx.taskId, points).exceptionOrNull()
            if (failure != null) return@exclusive say(errorOf(failure))
            val answer = if (ctx.bump != null) {
                PromptResolveRequest("ANSWERED", mapOf("action" to "revote"))
            } else {
                PromptResolveRequest.effortAnswer(points)
            }
            finish(ctx.promptId, answer)
        }
    }

    fun dismissPromptFrequencyVote() {
        val ctx = uiState.value.pendingFrequencyVoteContext ?: return
        if (ctx.bump != null) {
            uiState.value = uiState.value.backToBumpDialog()
            return
        }
        exclusive { finish(ctx.promptId, PromptResolveRequest("DISMISSED")) }
    }

    fun dismissPromptEffortVote() {
        val ctx = uiState.value.pendingEffortVoteContext ?: return
        if (ctx.bump != null) {
            uiState.value = uiState.value.backToBumpDialog()
            return
        }
        exclusive { finish(ctx.promptId, PromptResolveRequest("DISMISSED")) }
    }

    /** Answers the prompt (queued when offline), closes everything, and chains to the next re-vote. */
    private suspend fun finish(promptId: String, answer: PromptResolveRequest) {
        port.resolve(promptId, answer)
        uiState.value = uiState.value.promptDone()
        refresh()
    }
}

/** Runs [call] into a [Result]: cancellation propagates, any other failure is the result. */
internal suspend fun <T> resultOf(call: suspend () -> T): Result<T> = try {
    Result.success(call())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

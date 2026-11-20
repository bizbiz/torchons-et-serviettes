package com.torchons.app.ui.home

import android.app.Application
import com.torchons.app.R
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.data.repository.VoteRepository
import com.torchons.app.ui.common.UserErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.Response

/**
 * Unified prompt queue of the home screen (BUMP_EFFORT / VOTE_RATING /
 * VOTE_FREQUENCY / VOTE_EFFORT / RESOLVE_PRESENCE), split out of
 * HomeViewModel. It reads and writes the ViewModel's [uiState] (passed in,
 * never exposed) and asks the ViewModel to [refresh] when a prompt is done.
 * The vote dialogs live in [PromptVoteFlow].
 *
 * `internal` because HomeViewModel is its only user.
 */
internal class HomePromptActions(
    private val scope: CoroutineScope,
    private val application: Application,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val apiService: ApiService,
    taskRepository: TaskRepository,
    private val votes: VoteRepository,
    private val tokenManager: TokenManager,
    activeTasks: () -> List<Task>,
    refresh: () -> Unit,
) {
    private val flow = PromptVoteFlow(
        scope = scope,
        uiState = uiState,
        port = AppPromptVotePort(apiService, taskRepository, votes),
        activeTasks = activeTasks,
        refresh = refresh,
        errorOf = { UserErrors.from(application, it) },
        texts = PromptVoteTexts(
            networkNeededToVote = application.getString(R.string.prompt_vote_needs_network),
            effortStepNeedsNetwork = application.getString(R.string.prompt_effort_step_needs_network),
            taskUnavailable = application.getString(R.string.prompt_vote_task_unavailable),
        ),
    )

    /**
     * Fetch le prochain prompt non-resolu. Respecte la tolerance 2h : si un
     * prompt a ete affiche il y a moins de 2h, on ne fetch pas.
     *
     * Appele dans refresh() apres les projections.
     */
    suspend fun fetchNextPrompt() {
        try {
            val resp = apiService.getNextPrompt()
            if (resp.isSuccessful && resp.body() != null) {
                val prompt = resp.body()!!

                // Answered offline, answer still queued: the server lists it until the worker sends it.
                if (prompt.id in votes.promptsAnsweredOffline()) return
                // Never replace a prompt the user is already looking at (modal or vote dialog).
                val open = uiState.value
                if (PromptPolicy.isPromptOpen(
                        open.showPromptModal,
                        open.pendingFrequencyVoteContext != null,
                        open.pendingEffortVoteContext != null,
                    )
                ) return
                val isPresencePrompt = prompt.type == "RESOLVE_PRESENCE"
                // Presence and member-requested re-votes bypass the 2 h tolerance.
                val lastShown = tokenManager.lastPromptShownAtFlow.first()
                if (PromptPolicy.isHeldBackByTolerance(prompt.type, lastShown, System.currentTimeMillis())) return
                apiService.markPromptShown(prompt.id)
                if (!PromptPolicy.bypassesTolerance(prompt.type)) {
                    tokenManager.setLastPromptShownAt(System.currentTimeMillis())
                }
                uiState.value = uiState.value.copy(
                    currentPrompt = prompt,
                    showPromptModal = true,
                    // Mettre a jour le bandeau si c'est un prompt RESOLVE_PRESENCE
                    hasUnresolvedPresenceDays = isPresencePrompt,
                    unresolvedPresenceDaysCount = if (isPresencePrompt) {
                        @Suppress("UNCHECKED_CAST")
                        val days = prompt.context["uncertain_days"] as? List<*>
                        val count = prompt.context["uncertain_days_count"] as? Number
                        days?.size ?: count?.toInt() ?: 0
                    } else uiState.value.unresolvedPresenceDaysCount,
                )
            } else {
                // Pas de prompt pending -> pas de jours incertains
                uiState.value = uiState.value.copy(
                    hasUnresolvedPresenceDays = false,
                    unresolvedPresenceDaysCount = 0,
                )
            }
        } catch (_: Exception) {
            // offline — pas de prompt
        }
    }

    /** User repond au prompt (ANSWERED avec payload). */
    fun resolveCurrentPrompt(resolution: String, payload: Map<String, Any?>? = null) =
        flow.resolveCurrentPrompt(resolution, payload)

    /** User ferme le prompt (DISMISSED). */
    fun dismissCurrentPrompt() = flow.resolveCurrentPrompt("DISMISSED")

    // --- v0.28 : dispatch des prompts VOTE_FREQUENCY / VOTE_EFFORT (PromptVoteFlow) ---

    fun startBumpRevote() = flow.startBumpRevote()

    fun openFrequencyVoteFromPrompt(taskId: String, requesterName: String, bump: BumpRevote? = null) =
        flow.openFrequencyVoteFromPrompt(taskId, requesterName, bump)

    fun openEffortVoteFromPrompt(taskId: String, requesterName: String, bump: BumpRevote? = null) =
        flow.openEffortVoteFromPrompt(taskId, requesterName, bump)

    fun submitPromptFrequencyVote(request: FrequencyRequest) = flow.submitPromptFrequencyVote(request)

    fun submitPromptEffortVote(points: Int) = flow.submitPromptEffortVote(points)

    fun dismissPromptFrequencyVote() = flow.dismissPromptFrequencyVote()

    fun dismissPromptEffortVote() = flow.dismissPromptEffortVote()

    /** Reouvrir le prompt RESOLVE_PRESENCE depuis le bandeau. */
    fun reopenPresencePrompt() {
        scope.launch {
            try {
                val resp = apiService.getNextPrompt()
                if (resp.isSuccessful && resp.body() != null) {
                    val prompt = resp.body()!!
                    if (prompt.type == "RESOLVE_PRESENCE") {
                        apiService.markPromptShown(prompt.id)
                        uiState.value = uiState.value.copy(
                            currentPrompt = prompt,
                            showPromptModal = true,
                        )
                    }
                }
            } catch (_: Exception) {
                // Offline: the banner stays, the presence calendar (also online-only) says why.
            }
        }
    }
}

/** [PromptVotePort] over the repositories; the state reads are plain GETs. */
internal class AppPromptVotePort(
    private val apiService: ApiService,
    private val taskRepository: TaskRepository,
    private val votes: VoteRepository,
) : PromptVotePort {
    private suspend fun <T : Any> load(call: suspend () -> Response<T>): Result<T> = resultOf {
        val resp = call()
        if (!resp.isSuccessful) throw ApiHttpException.from(resp)
        resp.body() ?: throw IllegalStateException("empty body")
    }

    override suspend fun frequencyState(taskId: String): Result<FrequencyVotesState> =
        load { apiService.getFrequencyVotes(taskId) }

    override suspend fun effortState(taskId: String): Result<EffortVotesState> =
        load { apiService.getEffortVotes(taskId) }

    override suspend fun voteFrequency(taskId: String, request: FrequencyRequest) =
        taskRepository.voteFrequency(taskId, request)

    override suspend fun voteEffort(taskId: String, points: Int) = votes.voteEffort(taskId, points)

    override suspend fun resolve(promptId: String, request: PromptResolveRequest) =
        votes.resolvePrompt(promptId, request)
}

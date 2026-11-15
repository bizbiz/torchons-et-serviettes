package com.torchons.app.ui.home

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Unified prompt queue of the home screen (BUMP_EFFORT / VOTE_RATING /
 * VOTE_FREQUENCY / VOTE_EFFORT / RESOLVE_PRESENCE), split out of
 * HomeViewModel. It reads and writes the ViewModel's [uiState] (passed in,
 * never exposed) and asks the ViewModel to [refresh] when a prompt is done.
 *
 * `internal` because HomeViewModel is its only user.
 */
internal class HomePromptActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val apiService: ApiService,
    private val taskRepository: TaskRepository,
    private val tokenManager: TokenManager,
    private val activeTasks: () -> List<Task>,
    private val refresh: () -> Unit,
) {

    // --- Queue unifiee des prompts (BUMP_EFFORT / VOTE_RATING) ---

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
    fun resolveCurrentPrompt(resolution: String, payload: Map<String, Any?>? = null) {
        val prompt = uiState.value.currentPrompt ?: return
        val wasPresencePrompt = prompt.type == "RESOLVE_PRESENCE"
        val wasDismissed = resolution == "DISMISSED"
        scope.launch {
            try {
                apiService.resolvePrompt(prompt.id, PromptResolveRequest(resolution, payload))
            } catch (_: Exception) {}
            uiState.value = uiState.value.copy(
                currentPrompt = null,
                showPromptModal = false,
                // Si un RESOLVE_PRESENCE est DISMISSED ("Plus tard"), on garde
                // le bandeau visible — les taches restent freezees.
                hasUnresolvedPresenceDays = wasPresencePrompt && wasDismissed,
            )
            refresh()
        }
    }

    /** User ferme le prompt (DISMISSED). */
    fun dismissCurrentPrompt() {
        resolveCurrentPrompt("DISMISSED")
    }

    // --- v0.28 : dispatch des prompts VOTE_FREQUENCY / VOTE_EFFORT ---

    /**
     * Ouvre le FrequencyVoteDialog en reponse a un prompt VOTE_FREQUENCY.
     * Fetch le state a la volee, garde le promptId pour resolve apres submit.
     */
    fun openFrequencyVoteFromPrompt(taskId: String, requesterName: String) {
        val prompt = uiState.value.currentPrompt ?: return
        scope.launch {
            try {
                val resp = apiService.getFrequencyVotes(taskId)
                if (resp.isSuccessful) {
                    val state = resp.body()
                    val task = activeTasks().firstOrNull { it.id == taskId }
                    if (state != null && task != null) {
                        uiState.value = uiState.value.copy(
                            pendingFrequencyVoteContext = PromptVoteContext(
                                promptId = prompt.id,
                                taskId = taskId,
                                header = com.torchons.app.ui.common.voteHeader(task),
                                requesterName = requesterName,
                                frequencyState = state,
                            ),
                            // On garde currentPrompt pour pouvoir resolve, mais on
                            // cache le PromptModal de queue (le dialog vote prend la main).
                            showPromptModal = false,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun openEffortVoteFromPrompt(taskId: String, requesterName: String) {
        val prompt = uiState.value.currentPrompt ?: return
        scope.launch {
            try {
                val resp = apiService.getEffortVotes(taskId)
                if (resp.isSuccessful) {
                    val state = resp.body()
                    val task = activeTasks().firstOrNull { it.id == taskId }
                    if (state != null && task != null) {
                        uiState.value = uiState.value.copy(
                            pendingEffortVoteContext = PromptVoteContext(
                                promptId = prompt.id,
                                taskId = taskId,
                                header = com.torchons.app.ui.common.voteHeader(task),
                                requesterName = requesterName,
                                effortState = state,
                            ),
                            showPromptModal = false,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun submitPromptFrequencyVote(request: com.torchons.app.data.model.FrequencyRequest) {
        val ctx = uiState.value.pendingFrequencyVoteContext ?: return
        scope.launch {
            taskRepository.voteFrequency(ctx.taskId, request)
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest.frequencyAnswer(request))
            } catch (_: Exception) {}
            uiState.value = uiState.value.copy(
                pendingFrequencyVoteContext = null,
                currentPrompt = null,
            )
            refresh()
        }
    }

    fun submitPromptEffortVote(points: Int) {
        val ctx = uiState.value.pendingEffortVoteContext ?: return
        scope.launch {
            try {
                apiService.setEffort(ctx.taskId, mapOf("points" to points))
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest.effortAnswer(points))
            } catch (_: Exception) {}
            uiState.value = uiState.value.copy(
                pendingEffortVoteContext = null,
                currentPrompt = null,
            )
            refresh()
        }
    }

    fun dismissPromptFrequencyVote() {
        val ctx = uiState.value.pendingFrequencyVoteContext ?: return
        scope.launch {
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("DISMISSED"))
            } catch (_: Exception) {}
            uiState.value = uiState.value.copy(pendingFrequencyVoteContext = null, currentPrompt = null)
            refresh() // chain to the next queued re-vote, as a submit does
        }
    }

    fun dismissPromptEffortVote() {
        val ctx = uiState.value.pendingEffortVoteContext ?: return
        scope.launch {
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("DISMISSED"))
            } catch (_: Exception) {}
            uiState.value = uiState.value.copy(pendingEffortVoteContext = null, currentPrompt = null)
            refresh() // chain to the next queued re-vote, as a submit does
        }
    }

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
            } catch (_: Exception) {}
        }
    }
}

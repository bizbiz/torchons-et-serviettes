package com.torchons.app.ui.home

import android.app.Application
import com.torchons.app.R
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Task completions of the home screen (solo, dated, collective) with their
 * celebration, split out of HomeViewModel. It reads and writes the
 * ViewModel's [uiState] (passed in, never exposed) and asks the ViewModel to
 * [refresh] once a completion is saved.
 *
 * `internal` because HomeViewModel is its only user.
 */
internal class HomeCompletionActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val application: Application,
    private val taskRepository: TaskRepository,
    private val completionChecks: CompletionChecks,
    private val displayName: () -> String?,
    private val refresh: () -> Unit,
) {

    fun completeTask(taskId: String, previewPoints: Int? = null, previewReason: String? = null) =
        unlessDuplicate(taskId) {
        celebrateThen(taskId, previewPoints, previewReason, earnedByFor(null, previewPoints)) {
            taskRepository.completeTask(taskId).fold(
                onSuccess = { refresh() },
                onFailure = { uiState.value = uiState.value.copy(error = it.message) },
            )
        }
    }

    /**
     * Builds the celebration attribution list for a single completion:
     * (displayName, points). completedBy null = current user. Returns null
     * when points are unknown (offline preview failure) — the celebration
     * then falls back to its no-points variant.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val name = if (completedBy == null) {
            displayName() ?: return null
        } else {
            uiState.value.fairShare.firstOrNull { it.userId == completedBy }?.displayName
                ?: return null
        }
        return listOf(name to points)
    }

    fun completeTaskAtDate(
        taskId: String,
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, completedBy?.let(::listOf)) {
        celebrateThen(taskId, previewPoints, previewReason, earnedByFor(completedBy, previewPoints)) {
            taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                onSuccess = { refresh() },
                onFailure = { uiState.value = uiState.value.copy(error = it.message) },
            )
        }
    }

    /** Completion collective ("fait ensemble"). Online-first via endpoint dedie. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, participantUserIds) {
        celebrateThen(taskId, previewPoints, previewReason) {
            taskRepository.completeTaskCollective(
                taskId = taskId,
                participantUserIds = participantUserIds,
                completedAt = isoDate,
            ).fold(
                onSuccess = { response ->
                    // Refresh the celebration with the exact server values:
                    // per-participant attribution (split) + points/reason.
                    // Guard on celebratingTaskId — a late response must not
                    // write stale content after the celebration ended.
                    if (uiState.value.celebratingTaskId == taskId) {
                        val first = response.completions.firstOrNull()
                        val names = uiState.value.fairShare.associate { it.userId to it.displayName }
                        val earnedBy = response.completions.mapNotNull { c ->
                            names[c.completedBy]?.let { it to c.pointsEarned }
                        }.ifEmpty { null }
                        uiState.value = uiState.value.copy(
                            celebrationPoints = uiState.value.celebrationPoints ?: first?.pointsEarned,
                            celebrationReason = uiState.value.celebrationReason ?: first?.reason,
                            celebrationEarnedBy = earnedBy,
                        )
                    }
                    refresh()
                },
                onFailure = { err ->
                    val msg = when (err) {
                        is TaskRepository.CollectiveCompletionException -> when (err.httpCode) {
                            409 -> application.getString(R.string.home_collective_conflict)
                            else -> application
                                .getString(R.string.common_error_code_format, err.httpCode)
                        }
                        else -> err.message ?: application.getString(R.string.common_error_network)
                    }
                    uiState.value = uiState.value.copy(error = msg)
                },
            )
        }
    }

    /**
     * Affiche la celebration (emoji + confetti) pendant ~2s, puis execute
     * le bloc de completion. A la fin de la celebration, ajoute le taskId
     * a justCompletedIds pour que le HomeScreen affiche instantanement la
     * suggestion suivante du tampon (au lieu de re-flasher la meme carte
     * pendant que le serveur rattrape le retard de sync).
     */
    private fun celebrateThen(
        taskId: String,
        points: Int?,
        reason: String?,
        earnedBy: List<Pair<String, Int>>? = null,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            uiState.value = uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = uiState.value.celebrationTrigger + 1,
                celebrationPoints = points,
                celebrationReason = reason,
                celebrationEarnedBy = earnedBy,
            )
            // Run the completion CONCURRENTLY with the celebration so server
            // responses (exact collective split) can refresh the celebration
            // content while it is still on screen. The celebrating card stays
            // visible through celebratingTaskId; justCompletedIds takes over
            // the filtering at reset time.
            val work = launch { block() }
            kotlinx.coroutines.delay(3000L)
            uiState.value = uiState.value.copy(
                celebratingTaskId = null,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
                justCompletedIds = uiState.value.justCompletedIds + taskId,
            )
            work.join()
        }
    }

    /** Refuses a duplicate completion BEFORE the celebration (CompletionChecks.refusal); [by] null = me. */
    private fun unlessDuplicate(taskId: String, at: String? = null, by: List<String>? = null, go: () -> Unit) {
        scope.launch {
            val refusal = completionChecks.refusal(taskId, at, by)
            if (refusal == null) go() else uiState.value = uiState.value.copy(error = refusal)
        }
    }

    /** Points preview of a completion; null offline or on error (the UI falls back to /suggestions values). */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)
}

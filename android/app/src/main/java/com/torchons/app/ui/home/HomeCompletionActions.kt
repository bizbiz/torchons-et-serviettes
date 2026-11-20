package com.torchons.app.ui.home

import android.app.Application
import com.torchons.app.R
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.DuplicateCompletionException
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.TapGuard
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Task completions of the home screen (solo, dated, collective) with their
 * celebration, split out of HomeViewModel. It reads and writes the
 * ViewModel's [uiState] (passed in, never exposed) and asks the ViewModel to
 * [refresh] once a collective completion (online: the server has it) is saved. A solo completion
 * does not: the sync worker's push triggers the refresh (HomeViewModel).
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
            writeSolo(taskId, null, null)
        }
    }

    /**
     * The solo write. No refresh here: the op is still in the queue and the server does not have
     * it, so a refetch would bring back the suggestion just completed (docs/cache-conventions.md).
     * HomeViewModel refreshes when the sync worker has pushed.
     */
    private suspend fun writeSolo(taskId: String, isoDate: String?, completedBy: String?) {
        taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
            onSuccess = { },
            onFailure = { error ->
                val duplicate = (error as? DuplicateCompletionException)
                    ?.let { completionChecks.duplicateMessage(it.twin) }
                    ?.let(::UserError)
                uiState.value = uiState.value.copy(
                    celebratingTaskId = null,
                    celebrationPoints = null,
                    celebrationReason = null,
                    celebrationEarnedBy = null,
                    error = duplicate ?: UserErrors.from(application, error),
                )
            },
        )
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
            writeSolo(taskId, isoDate, completedBy)
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
                    val msg = UserErrors.from(application, err) {
                        if (it.httpCode == 409) application.getString(R.string.home_collective_conflict) else null
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
            try {
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
            } finally {
                tapGuard.release(taskId)
            }
        }
    }

    /**
     * Refuses a duplicate completion BEFORE the celebration (CompletionChecks.refusal); [by] null =
     * me. A tap on a task whose previous completion is still being handled is ignored.
     */
    private fun unlessDuplicate(taskId: String, at: String? = null, by: List<String>? = null, go: () -> Unit) {
        if (!tapGuard.tryAcquire(taskId)) return
        scope.launch {
            val refusal = completionChecks.refusal(taskId, at, by)
            if (refusal == null) {
                go()
            } else {
                tapGuard.release(taskId)
                uiState.value = uiState.value.copy(error = UserError(refusal))
            }
        }
    }

    private val tapGuard = TapGuard()

    /** Points preview of a completion; null offline or on error (the UI falls back to /suggestions values). */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)
}

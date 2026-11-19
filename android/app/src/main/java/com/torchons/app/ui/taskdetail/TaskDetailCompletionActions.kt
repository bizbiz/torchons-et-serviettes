package com.torchons.app.ui.taskdetail

import com.torchons.app.R
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.util.CompletionDateLabels
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Task completions of the detail screen (solo, dated, collective) with their
 * celebration, split out of TaskDetailViewModel. It reads and writes the
 * ViewModel's [uiState] (passed in, never exposed).
 *
 * `internal` because TaskDetailViewModel is its only user.
 */
internal class TaskDetailCompletionActions(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<TaskDetailUiState>,
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val completionChecks: CompletionChecks,
    private val appContext: android.content.Context,
    private val currentUserId: () -> String?,
) {

    fun completeTask() = unlessDuplicate {
        celebrate(
            resolve = {
                val preview = fetchCompletionPreview(
                    taskId, CompletionDateLabels.toIsoNoonUtc(LocalDate.now()), 1,
                )
                CelebrationContent(
                    points = preview?.finalPoints,
                    reason = preview?.reason,
                    earnedBy = earnedByFor(null, preview?.finalPoints),
                )
            },
            write = {
                taskRepository.completeTask(taskId).fold(
                    onSuccess = {
                        uiState.value = uiState.value.copy(
                            completionMessage = appContext.getString(R.string.common_task_completed),
                        )
                    },
                    onFailure = { error ->
                        uiState.value = uiState.value.copy(error = UserErrors.from(appContext, error))
                    },
                )
            },
        )
    }

    /** Deja fait : completion solo a une date passee (optionnellement pour un autre user). */
    fun completeTaskAtDate(
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(isoDate, completedBy?.let(::listOf)) {
        celebrate(
            resolve = {
                CelebrationContent(previewPoints, previewReason, earnedByFor(completedBy, previewPoints))
            },
            write = {
                taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                    onSuccess = {
                        uiState.value = uiState.value.copy(
                            completionMessage = appContext.getString(R.string.common_task_completed),
                        )
                    },
                    onFailure = { error ->
                        uiState.value = uiState.value.copy(error = UserErrors.from(appContext, error))
                    },
                )
            },
        )
    }

    /** Fait ensemble : completion collective (N participants, split ceil). */
    fun completeTaskCollective(
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(isoDate, participantUserIds) {
        celebrate(
            resolve = {
                // Points par personne : preview du dialog, ou fetch (chemin
                // rapide 2-pers). Le preview divise deja par le nb de participants.
                val preview = if (previewPoints != null) null
                    else fetchCompletionPreview(
                        taskId,
                        isoDate ?: CompletionDateLabels.toIsoNoonUtc(LocalDate.now()),
                        participantUserIds.size,
                    )
                val points = previewPoints ?: preview?.finalPoints
                CelebrationContent(
                    points = points,
                    reason = previewReason ?: preview?.reason,
                    earnedBy = earnedByForMany(participantUserIds, points),
                )
            },
            write = {
                taskRepository.completeTaskCollective(
                    taskId = taskId,
                    participantUserIds = participantUserIds,
                    completedAt = isoDate,
                ).fold(
                    onSuccess = {
                        uiState.value = uiState.value.copy(
                            completionMessage = appContext.resources.getQuantityString(
                                R.plurals.common_task_completed_by_count,
                                participantUserIds.size,
                                participantUserIds.size,
                            ),
                        )
                    },
                    onFailure = { err ->
                        val msg = UserErrors.from(appContext, err) {
                            if (it.httpCode == 409) appContext.getString(R.string.common_collective_conflict) else null
                        }
                        uiState.value = uiState.value.copy(error = msg)
                    },
                )
            },
        )
    }

    /** Contenu enrichi d'une celebration : points + raison + attribution. */
    private data class CelebrationContent(
        val points: Int?,
        val reason: String?,
        val earnedBy: List<Pair<String, Int>>?,
    )

    /**
     * Attribution single (displayName, points). [completedBy] null = user
     * courant. Null si points ou nom inconnus (preview offline) — la
     * celebration retombe sur sa variante sans points.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val names = uiState.value.userNames
        val name = if (completedBy == null) {
            names[currentUserId()] ?: return null
        } else {
            names[completedBy] ?: return null
        }
        return listOf(name to points)
    }

    /**
     * Attribution collective : une entree (displayName, pointsParPersonne) par
     * participant. [points] est deja le split par personne (final_per_user).
     */
    private fun earnedByForMany(participantUserIds: List<String>, points: Int?): List<Pair<String, Int>>? {
        if (points == null || participantUserIds.isEmpty()) return null
        val names = uiState.value.userNames
        return participantUserIds.mapNotNull { id -> names[id]?.let { it to points } }.ifEmpty { null }
    }

    /**
     * Celebration visuelle (card + confetti) pendant ~3s : emoji immediat, puis
     * points + attribution des que [resolve] les renvoie (preview). L'ecriture
     * Room reelle ([write]) est faite APRES l'animation (parite Accueil /
     * onglet Taches).
     */
    private fun celebrate(
        resolve: suspend () -> CelebrationContent,
        write: suspend () -> Unit,
    ) {
        scope.launch {
            uiState.value = uiState.value.copy(
                isCelebrating = true,
                celebrationTrigger = uiState.value.celebrationTrigger + 1,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            val filler = launch {
                val content = resolve()
                if (uiState.value.isCelebrating) {
                    uiState.value = uiState.value.copy(
                        celebrationPoints = content.points,
                        celebrationReason = content.reason,
                        celebrationEarnedBy = content.earnedBy,
                    )
                }
            }
            kotlinx.coroutines.delay(3000L)
            filler.cancel()
            uiState.value = uiState.value.copy(
                isCelebrating = false,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            write()
        }
    }

    /** Refuses a duplicate completion BEFORE the celebration (CompletionChecks.refusal); [by] null = me. */
    private fun unlessDuplicate(at: String? = null, by: List<String>? = null, go: () -> Unit) {
        scope.launch {
            val refusal = completionChecks.refusal(taskId, at, by)
            if (refusal == null) go() else uiState.value = uiState.value.copy(error = UserError(refusal))
        }
    }

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)
}

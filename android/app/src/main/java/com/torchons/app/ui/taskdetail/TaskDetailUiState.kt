package com.torchons.app.ui.taskdetail

import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.ui.common.UserError
import com.torchons.app.util.TaskUrgencyCalculator

data class TaskRating(
    val userId: String?,
    val displayName: String,
    val affinity: Int?,
    val effortTimeMin: Int?,
)

data class TaskDetailUiState(
    val isLoading: Boolean = false,
    val task: Task? = null,
    val completions: List<Completion> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    /** Members who left, by id: author labels only, never a selector. */
    val formerMembers: Map<String, FormerMember> = emptyMap(),
    val ratings: List<TaskRating> = emptyList(),
    val avgEffortTimeMin: Int? = null,
    val scorePoints: Int? = null,
    val urgencyState: TaskUrgencyCalculator.State = TaskUrgencyCalculator.State.GREEN,
    val daysUntilDue: Int = 0,
    val countdownLabel: String = "",
    val history: List<TaskHistoryEvent> = emptyList(),
    /** Contexte principal de la tache, tel qu'il arrive avec elle. */
    val room: TaskContextRef? = null,
    /** Contexte de notation pour le modal unifie. Non-null = modal ouvert. */
    val ratingContext: PendingAffinity? = null,
    val editingCompletion: Completion? = null,
    val error: UserError? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    /** True pendant un refresh declenche par le pull-to-refresh (indicateur
     *  de tirage). Distinct de [isLoading] (spinner plein ecran au 1er load). */
    val isRefreshing: Boolean = false,
    /** True pendant l'animation de celebration post-completion. */
    val isCelebrating: Boolean = false,
    /** Compteur incremente a chaque celebration pour relancer konfetti. */
    val celebrationTrigger: Int = 0,
    /** Points affiches dans la celebration (null = inconnu / offline). */
    val celebrationPoints: Int? = null,
    /** Raison bonus/malus de la celebration (BONUS_RETARD, MALUS_AVANCE, ...). */
    val celebrationReason: String? = null,
    /** Attribution par participant (displayName, points) dans la celebration. */
    val celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /** Preview bonus/malus pour affichage dans le header urgence de la fiche.
     * null tant que le fetch n'a pas abouti ou si pas de recurrence. */
    val bonusPreview: com.torchons.app.data.model.CompletionPreview? = null,
    /** v0.28 : etat des votes de frequence (consensus + others + pending). */
    val frequencyVotes: FrequencyVotesState? = null,
    /** v0.28 : etat des votes d'effort (consensus + others + pending). */
    val effortVotes: EffortVotesState? = null,
    /** v0.28 : modal "+ d'info" frequence ouvert. */
    val showFrequencyInfo: Boolean = false,
    /** v0.29 : modal "+ d'info" effort ouvert. */
    val showEffortInfo: Boolean = false,
    /** v0.28 : modal "+ d'info" affinite ouvert. */
    val showAffinityInfo: Boolean = false,
    /** v0.28 : FrequencyVoteDialog ouvert (depuis le bandeau ou + d'info). */
    val showFrequencyVoteDialog: Boolean = false,
    /** v0.28 : EffortVoteDialog ouvert (depuis le bandeau ou + d'info). */
    val showEffortVoteDialog: Boolean = false,
)

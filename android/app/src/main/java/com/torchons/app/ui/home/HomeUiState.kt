package com.torchons.app.ui.home

import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.UserPrompt
import com.torchons.app.ui.common.UserError

/**
 * Etat UI de l'accueil.
 *
 * @param hasCachedData true des qu'au moins un snapshot non-null a ete observe
 *   depuis le cache Room. Permet de distinguer "ecran vide initial" de
 *   "tout est frais reellement".
 * @param lastRefreshFailed true si le dernier refresh API a echoue (au moins
 *   un des sub-fetches). Combine avec hasCachedData = false, indique au UI
 *   qu'on est dans un etat "rien a afficher car backend injoignable".
 */
/** Contexte d'un FrequencyVoteDialog / EffortVoteDialog ouvert via prompt (v0.28). */
data class PromptVoteContext(
    val promptId: String,
    val taskId: String,
    val header: com.torchons.app.ui.common.VoteTaskHeader,
    val requesterName: String,
    val frequencyState: com.torchons.app.data.model.FrequencyVotesState? = null,
    val effortState: com.torchons.app.data.model.EffortVotesState? = null,
    /** Non-null: step of the « souvent en retard » chain (frequency, then effort). */
    val bump: BumpRevote? = null,
)

/**
 * « Tâche souvent en retard » → Revoter: frequency vote (step 1), then effort
 * vote (step 2). The BUMP_EFFORT prompt is resolved only after step 2, so no
 * other queued prompt can slip in between (Lo's bug of 2026-11-17).
 */
data class BumpRevote(val latePercent: Int, val windowDays: Int)

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val fairShare: List<FairShareStatus> = emptyList(),
    val pendingAffinity: PendingAffinity? = null,
    val showAffinityPopup: Boolean = false,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    val error: UserError? = null,
    /** Id de la tache en cours de "celebration" (animation post-completion). */
    val celebratingTaskId: String? = null,
    /** Compteur incremente a chaque celebration pour relancer l'animation konfetti. */
    val celebrationTrigger: Int = 0,
    /** Points gagnes a afficher dans la celebration (preview ou response reelle). */
    val celebrationPoints: Int? = null,
    /** Raison du multiplicateur (BONUS_RETARD / MALUS_AVANCE / FIRST_COMPLETION / NEUTRAL / PERSO). */
    val celebrationReason: String? = null,
    /**
     * Per-participant attribution shown in the celebration: (displayName,
     * pointsEarned) pairs. Single completion -> one entry; collective ->
     * one entry per participant (exact server split).
     */
    val celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /**
     * Set des task_ids recemment completes cote client pour lesquels le
     * backend n'a pas encore integre la completion (SyncWorker pas encore
     * passe). Le HomeScreen les filtre pour afficher la suggestion suivante
     * du tampon au lieu de montrer la meme tache une seconde fois. Les ids
     * sont automatiquement nettoyes de ce set des que le backend ne les
     * renvoie plus dans /suggestions (= il a rattrape le retard de sync).
     */
    val justCompletedIds: Set<String> = emptySet(),
    /**
     * v0.16+ : set des task_ids dismiss par l'user sur l'ecran Home (clic
     * sur le ✕ d'une SuggestionCard). Session-only in-memory : reset au
     * cold start, preservee a travers les LifecycleResumeEffect. Le HomeScreen
     * les filtre comme pour justCompletedIds. Quand un tier est entierement
     * dismiss (remainingByTier[tier] == 0), le ViewModel clear ce set et
     * emet un message sur `recycleToast`.
     */
    val dismissedIds: Set<String> = emptySet(),
    /**
     * True quand il y a des jours incertains non resolus (home freeze v2).
     * Le bandeau s'affiche en haut de la home.
     */
    val hasUnresolvedPresenceDays: Boolean = false,
    /** Nombre de jours incertains (pour le bandeau). */
    val unresolvedPresenceDaysCount: Int = 0,
    /**
     * Donnees des 2 cards projection (v0.8.0 magic buttons). Tout null tant
     * que le premier fetch n'a pas abouti.
     */
    val retard: RetardResponse? = null,
    val pace: PaceResponse? = null,
    val maintenance: MaintenanceRateResponse? = null,
    /**
     * Historique du retard du foyer sur les 30 derniers jours, fetch en
     * parallele pour la mini-courbe Home (period=month, sans trim creation).
     */
    val retardTimelineMonth: com.torchons.app.data.model.RetardTimelineResponse? = null,
    /** Prompt de la queue unifiee (BUMP_EFFORT / VOTE_RATING / VOTE_FREQUENCY / VOTE_EFFORT). */
    val currentPrompt: UserPrompt? = null,
    /** v0.28 : si un VOTE_FREQUENCY est dispatche, contexte du dialog en cours. */
    val pendingFrequencyVoteContext: PromptVoteContext? = null,
    /** v0.28 : si un VOTE_EFFORT est dispatche, contexte du dialog en cours. */
    val pendingEffortVoteContext: PromptVoteContext? = null,
    /** True quand le modal prompt est actif. */
    val showPromptModal: Boolean = false,
)

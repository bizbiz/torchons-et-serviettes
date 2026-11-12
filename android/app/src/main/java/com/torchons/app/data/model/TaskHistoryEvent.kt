package com.torchons.app.data.model

/** Type d'evenement affiche dans l'historique de modification d'une tache. */
enum class TaskHistoryEventType {
    VOTE_AFFINITY,
    VOTE_EFFORT,
    VOTE_FREQUENCY,
    TASK_UPDATE,
    /** The task's creation (server type `task_create`, since 2026-11-13). */
    TASK_CREATE,
    /** A member left the household: the server removed them from this task. */
    MEMBER_LEFT,
    UNKNOWN,
}

/**
 * Evenement d'historique de modification d'une tache — modele du domaine,
 * decouple du DTO backend et de l'entite Room.
 */
data class TaskHistoryEvent(
    val id: Long,
    val type: TaskHistoryEventType,
    val actorUserId: String? = null,
    val actorName: String = "",
    val at: String? = null,
    /** Valeur votee pour les evenements de type VOTE_* (score, points ou every_n_days). */
    val voteValue: Int? = null,
    val consensusBefore: Int? = null,
    val consensusAfter: Int? = null,
    /** Pour TASK_UPDATE : liste des noms de champs modifies. */
    val fieldsChanged: List<String> = emptyList(),
    /** Ancienne valeur serialisee (JSON brut) pour affichage "ancien -> nouveau". */
    val oldJson: String? = null,
    val newJson: String? = null,
)

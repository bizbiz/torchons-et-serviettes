package com.torchons.app.data.model

/**
 * Modeles de presence v2 (home freeze).
 *
 * L'ancien systeme (PresenceWorker background + POST /presence avec
 * is_at_home booleen) a ete remplace par un checkin WiFi positif uniquement
 * (POST /presence/checkin sans payload) + resolution manuelle des jours
 * incertains via le prompt RESOLVE_PRESENCE.
 *
 * P2 (design-foyer-settings-presence.md decision 4) ajoute un calendrier de
 * presence editable, foyer-scope. GET /api/presence?from=&to= et
 * PUT /api/presence partagent ces DTO.
 */

/** Une ligne presence_days renvoyee par GET/PUT /api/presence. */
data class PresenceDayDto(
    /** Jour calendaire ISO "YYYY-MM-DD". */
    val date: String,
    /** confirmed_present / confirmed_absent. */
    val status: String,
    /** auto_wifi / manual / foyer_init. */
    val source: String,
)

/**
 * Un jour a poser via PUT /api/presence.
 *
 * status :
 * - "present" -> confirmed_present (source manual)
 * - "absent"  -> confirmed_absent (source manual)
 * - "clear"   -> supprime la ligne (le jour redevient incertain)
 */
data class PresenceDayPut(
    val date: String,
    val status: String,
)

/** Statut visuel d'un jour dans la grille calendrier partagee. */
enum class PresenceCellState {
    /** confirmed_present — fond vert. */
    PRESENT,

    /** confirmed_absent — fond rouge doux. */
    ABSENT,

    /** Aucune ligne — fond gris (compte absent par defaut cote gel). */
    UNKNOWN,
}

/** D'ou vient l'info du jour (pilote l'icone source de la case). */
enum class PresenceSource {
    AUTO_WIFI,
    MANUAL,
    NONE,
}

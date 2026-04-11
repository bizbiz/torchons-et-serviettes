package com.torchons.app.core.network

/**
 * Etat de connexion au backend, observe par l'UI pour afficher le bandeau
 * "hors ligne" et adapter le comportement de chargement.
 */
sealed interface ConnectionState {
    /** Le backend repond. */
    data object Online : ConnectionState

    /**
     * Le backend ne repond pas (echec reseau ou code HTTP 5xx).
     *
     * @param sinceEpochMs timestamp epoch ms du moment ou on a bascule offline.
     *        Permet d'afficher "Hors ligne depuis X minutes" si pertinent.
     */
    data class Offline(val sinceEpochMs: Long) : ConnectionState
}

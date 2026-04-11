package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entite generique de "snapshot de vue" : on stocke en JSON les reponses
 * derivees du backend (suggestions, in-progress, fair-share, settings, etc.)
 * qu'on ne veut pas modeliser en tables structurees.
 *
 * Pattern key/value :
 * - key : identifiant logique de la vue (ex: "home_suggestions", "fair_share_status")
 * - jsonContent : JSON brut tel que renvoye par l'API
 * - updatedAt : timestamp epoch ms du dernier refresh reussi
 *
 * Permet d'afficher instantanement la derniere vue connue, et d'afficher
 * son age dans le bandeau "hors ligne".
 */
@Entity(tableName = "view_snapshots")
data class ViewSnapshotEntity(
    @PrimaryKey val key: String,
    val jsonContent: String,
    val updatedAt: Long,
) {
    companion object {
        const val KEY_HOME_SUGGESTIONS = "home_suggestions"
        const val KEY_HOME_IN_PROGRESS = "home_in_progress"
        const val KEY_HOME_FAIR_SHARE = "home_fair_share"
        const val KEY_HOME_PENDING_AFFINITY = "home_pending_affinity"
        const val KEY_USER_SETTINGS = "user_settings"
    }
}

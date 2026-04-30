package com.torchons.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.ViewSnapshotEntity
import com.torchons.app.data.model.DismissRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Suggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository pour les "snapshots de vues derivees" affichees sur l'accueil.
 *
 * Ces vues (suggestions, in-progress, fair-share, pending-affinity, settings)
 * sont calculees par le backend et n'ont pas vraiment de modele relationnel
 * cote client. On les stocke en JSON dans la table view_snapshots, ce qui
 * permet de les afficher instantanement au demarrage meme si on est offline.
 *
 * Pattern : observe* retourne un Flow<List<X>?> ou null = pas encore de cache.
 * refresh* fait l'appel API et upsert le snapshot. Le Flow emet automatiquement.
 */
@Singleton
class HomeRepository @Inject constructor(
    private val apiService: ApiService,
    private val snapshotDao: ViewSnapshotDao,
) {

    private val gson = Gson()

    // -------------------------------------------------------------------
    // Suggestions
    // -------------------------------------------------------------------

    fun observeSuggestions(): Flow<List<Suggestion>?> =
        snapshotDao.observe(ViewSnapshotEntity.KEY_HOME_SUGGESTIONS).map { entity ->
            entity?.let { decode<List<Suggestion>>(it.jsonContent) }
        }

    /**
     * Resultat du refresh suggestions (v0.16+).
     *
     * @param success vrai si l'API a repondu avec succes
     * @param remainingByTier nombre de candidats restants par tier apres
     *   filtrage dismiss ; null si success=false. Le ViewModel s'en sert
     *   pour detecter qu'un tier est epuise (= 0) et declencher le recycle.
     */
    data class SuggestionsRefreshResult(
        val success: Boolean,
        val remainingByTier: Map<String, Int>? = null,
    )

    /**
     * Refetch /suggestions avec une dismiss list optionnelle.
     *
     * Le cache Room ne stocke que la liste `suggestions` — `remainingByTier`
     * depend de la dismiss list volatile (session-only, pas cachee offline).
     */
    suspend fun refreshSuggestions(
        excludeIds: Set<String> = emptySet(),
    ): SuggestionsRefreshResult {
        return try {
            val excludeParam = excludeIds.takeIf { it.isNotEmpty() }?.joinToString(",")
            val response = apiService.getSuggestions(exclude = excludeParam)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.suggestions ?: emptyList()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_HOME_SUGGESTIONS,
                        jsonContent = gson.toJson(data),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                SuggestionsRefreshResult(
                    success = true,
                    remainingByTier = body?.remainingByTier ?: emptyMap(),
                )
            } else {
                SuggestionsRefreshResult(success = false)
            }
        } catch (_: Exception) {
            SuggestionsRefreshResult(success = false)
        }
    }

    /**
     * Log analytics fire-and-forget d'un ✕ sur carte Home. Les erreurs
     * (reseau, backend, task supprimee entre-temps) sont silencieuses —
     * on ne veut pas bloquer l'UX pour un log best-effort.
     */
    suspend fun logSuggestionDismiss(taskId: String, tier: String) {
        try {
            apiService.logSuggestionDismiss(DismissRequest(taskId = taskId, tier = tier))
        } catch (_: Exception) {
            // drop silencieux
        }
    }

    // -------------------------------------------------------------------
    // Fair-share
    // -------------------------------------------------------------------

    fun observeFairShare(): Flow<List<FairShareStatus>?> =
        snapshotDao.observe(ViewSnapshotEntity.KEY_HOME_FAIR_SHARE).map { entity ->
            entity?.let { decode<List<FairShareStatus>>(it.jsonContent) }
        }

    suspend fun refreshFairShare(): Boolean {
        return try {
            val response = apiService.getFairShareStatus()
            if (response.isSuccessful) {
                val data = response.body() ?: emptyList()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_HOME_FAIR_SHARE,
                        jsonContent = gson.toJson(data),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Pending affinity
    // -------------------------------------------------------------------

    fun observePendingAffinity(): Flow<List<PendingAffinity>?> =
        snapshotDao.observe(ViewSnapshotEntity.KEY_HOME_PENDING_AFFINITY).map { entity ->
            entity?.let { decode<List<PendingAffinity>>(it.jsonContent) }
        }

    suspend fun refreshPendingAffinity(expiryMonths: Int): Boolean {
        return try {
            val response = apiService.getPendingAffinity(expiryMonths)
            if (response.isSuccessful) {
                val data = response.body() ?: emptyList()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_HOME_PENDING_AFFINITY,
                        jsonContent = gson.toJson(data),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Settings (Map<String, Any> brut)
    // -------------------------------------------------------------------

    fun observeUserSettings(): Flow<Map<String, Any>?> =
        snapshotDao.observe(ViewSnapshotEntity.KEY_USER_SETTINGS).map { entity ->
            entity?.let { decode<Map<String, Any>>(it.jsonContent) }
        }

    suspend fun refreshUserSettings(): Boolean {
        return try {
            val response = apiService.getSettings()
            if (response.isSuccessful) {
                val data = response.body() ?: emptyMap()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_USER_SETTINGS,
                        jsonContent = gson.toJson(data),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Age du cache pour le bandeau hors ligne
    // -------------------------------------------------------------------

    /**
     * Retourne le timestamp ms du plus ancien snapshot home (suggestions,
     * fair-share, pending-affinity). Permet d'afficher
     * "donnees du JJ/MM HH:mm" dans le bandeau.
     */
    suspend fun getHomeSnapshotAge(): Long? {
        return snapshotDao.getOldestUpdatedAt(
            listOf(
                ViewSnapshotEntity.KEY_HOME_SUGGESTIONS,
                ViewSnapshotEntity.KEY_HOME_FAIR_SHARE,
                ViewSnapshotEntity.KEY_HOME_PENDING_AFFINITY,
            )
        )
    }

    // -------------------------------------------------------------------
    // Decoder JSON helper
    // -------------------------------------------------------------------

    private inline fun <reified T> decode(json: String): T? {
        return try {
            val type = object : TypeToken<T>() {}.type
            gson.fromJson<T>(json, type)
        } catch (_: Exception) {
            null
        }
    }
}

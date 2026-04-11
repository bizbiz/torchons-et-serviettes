package com.torchons.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.ViewSnapshotEntity
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.TaskInProgress
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

    suspend fun refreshSuggestions(freshnessThreshold: Double): Boolean {
        return try {
            val response = apiService.getSuggestions(freshnessThreshold)
            if (response.isSuccessful) {
                val data = response.body() ?: emptyList()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_HOME_SUGGESTIONS,
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
    // In-progress
    // -------------------------------------------------------------------

    fun observeInProgress(): Flow<List<TaskInProgress>?> =
        snapshotDao.observe(ViewSnapshotEntity.KEY_HOME_IN_PROGRESS).map { entity ->
            entity?.let { decode<List<TaskInProgress>>(it.jsonContent) }
        }

    suspend fun refreshInProgress(): Boolean {
        return try {
            val response = apiService.getInProgress()
            if (response.isSuccessful) {
                val data = response.body() ?: emptyList()
                snapshotDao.upsert(
                    ViewSnapshotEntity(
                        key = ViewSnapshotEntity.KEY_HOME_IN_PROGRESS,
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
     * in-progress, fair-share, pending-affinity). Permet d'afficher
     * "donnees du JJ/MM HH:mm" dans le bandeau.
     */
    suspend fun getHomeSnapshotAge(): Long? {
        return snapshotDao.getOldestUpdatedAt(
            listOf(
                ViewSnapshotEntity.KEY_HOME_SUGGESTIONS,
                ViewSnapshotEntity.KEY_HOME_IN_PROGRESS,
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

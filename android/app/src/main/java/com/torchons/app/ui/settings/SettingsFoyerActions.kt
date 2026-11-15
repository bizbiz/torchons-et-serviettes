package com.torchons.app.ui.settings

import android.content.Context
import android.util.Log
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Foyer
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.settings.SettingsViewModel.LeaveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Household (foyer) actions of the settings screens: rename, toggles,
 * switch, create, leave, and the server reconciliation of the foyer list.
 * Split out of SettingsViewModel, which keeps the public methods and the
 * flows the screens observe: [currentFoyer], [allFoyers] and [leaveState]
 * are passed in (never exposed here), [loadAll] asks the ViewModel to reload.
 *
 * `internal` because SettingsViewModel is its only user.
 */
internal class SettingsFoyerActions(
    private val scope: CoroutineScope,
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val taskRepository: TaskRepository,
    private val appContext: Context,
    private val currentFoyer: MutableStateFlow<Foyer?>,
    private val allFoyers: MutableStateFlow<List<Foyer>>,
    private val leaveState: MutableStateFlow<LeaveState>,
    private val loadAll: () -> Unit,
) {

    /** v0.32 : toggle gamification du foyer courant. Tout membre peut
     *  toggler (cf design review Q4) — friction cote UI via dialog de
     *  confirmation mentionnant l'impact sur l'autre membre. */
    fun setCurrentFoyerGamificationEnabled(enabled: Boolean) {
        val foyer = currentFoyer.value ?: return
        // Optimistic update : on flip le local d'abord pour reactivite UI.
        currentFoyer.value = foyer.copy(gamificationEnabled = enabled)
        allFoyers.value = allFoyers.value.map {
            if (it.id == foyer.id) it.copy(gamificationEnabled = enabled) else it
        }
        scope.launch {
            tokenManager.setCurrentFoyerGamificationEnabled(enabled)
            try {
                val resp = apiService.updateFoyerGamification(
                    foyer.id,
                    mapOf("enabled" to enabled),
                )
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) {
                        currentFoyer.value = updated
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "setCurrentFoyerGamificationEnabled failed", e)
            }
        }
    }
    /**
     * Renomme le foyer courant (edition inline depuis l'ecran Foyer).
     * Reutilise PUT /api/foyers/{id} (deja utilise par FoyersScreen). Update
     * optimiste local pour reactivite UI, puis sync backend.
     */
    fun renameCurrentFoyer(newName: String) {
        val foyer = currentFoyer.value ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == foyer.name) return
        currentFoyer.value = foyer.copy(name = trimmed)
        allFoyers.value = allFoyers.value.map {
            if (it.id == foyer.id) it.copy(name = trimmed) else it
        }
        scope.launch {
            try {
                val resp = apiService.updateFoyer(foyer.id, mapOf("name" to trimmed))
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) currentFoyer.value = updated
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "renameCurrentFoyer failed", e)
            }
        }
    }

    /**
     * Toggle la mise en pause des taches "a la maison" quand tout le monde
     * est absent (home freeze) pour le foyer courant. Reutilise
     * PUT /api/foyers/{id} (champ home_freeze_enabled, deja gere backend).
     */
    fun setCurrentFoyerHomeFreezeEnabled(enabled: Boolean) {
        val foyer = currentFoyer.value ?: return
        currentFoyer.value = foyer.copy(homeFreezeEnabled = enabled)
        allFoyers.value = allFoyers.value.map {
            if (it.id == foyer.id) it.copy(homeFreezeEnabled = enabled) else it
        }
        scope.launch {
            try {
                val resp = apiService.updateFoyer(
                    foyer.id,
                    mapOf("home_freeze_enabled" to enabled),
                )
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) currentFoyer.value = updated
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "setCurrentFoyerHomeFreezeEnabled failed", e)
            }
        }
    }

    /**
     * Switch backend (regenere JWT) -> wipe du cache Room local -> refresh
     * complet du nouveau foyer. Helper interne reutilise par switchToFoyer
     * et createAndSwitchToFoyer. Reprend exactement le pattern de
     * FoyersScreen (option A grille v0.7.0).
     */
    /** P4a.1 — re-fetch foyers (suspend, reconciliation serveur). */
    suspend fun fetchFoyers() {
        try {
            val fResp = apiService.getMyFoyers()
            if (fResp.isSuccessful) {
                val foyers = fResp.body() ?: emptyList()
                // Exclure les foyers quittés (cf loadAll : /foyers ne filtre
                // pas left_at, on ne liste que les membres actifs).
                allFoyers.value = foyers.filter { it.role != "former_member" }
                val current = foyers.find { it.isCurrent }
                currentFoyer.value = current
                if (current != null) {
                    tokenManager.setCurrentFoyerGamificationEnabled(
                        current.gamificationEnabled,
                    )
                    tokenManager.setCurrentFoyerName(current.name)
                }
            }
        } catch (_: Exception) {}
    }

    /** P4a.1 Bug A — l'ecran Foyer reflete un foyer nouvellement cree ou
     *  quitte SANS refresh manuel. Les instances SettingsViewModel de
     *  SettingsScreen et FoyerSettingsScreen sont distinctes (hiltViewModel
     *  par backstack) : l'ecran Foyer doit re-fetcher a son apparition. */
    fun refreshFoyers() {
        scope.launch { fetchFoyers() }
    }

    private suspend fun doSwitchAndRefresh(foyerId: String) {
        val result = authRepository.switchFoyer(foyerId)
        if (result.isSuccess) {
            taskRepository.wipeLocalCache()
            taskRepository.refreshTasks()
            taskRepository.refreshContexts()
            taskRepository.refreshUsers()
            taskRepository.refreshCompletions(limit = 2000)
            loadAll()
        }
    }

    /** Bascule vers un autre foyer existant (feuille "Changer de foyer"). */
    fun switchToFoyer(foyerId: String) {
        scope.launch { doSwitchAndRefresh(foyerId) }
    }

    /**
     * Cree un nouveau foyer puis bascule dessus (bouton "Creer un nouveau
     * foyer" des Reglages, design decision 3). Reutilise POST /api/foyers
     * (meme endpoint que l'ancien ecran Mes foyers — aucun endpoint invente).
     */
    fun createAndSwitchToFoyer(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            try {
                val resp = apiService.createFoyer(FoyerCreateRequest(trimmed))
                if (resp.isSuccessful) {
                    val created = resp.body()
                    if (created != null) {
                        doSwitchAndRefresh(created.id)
                    } else {
                        loadAll()
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "createAndSwitchToFoyer failed", e)
            }
        }
    }

    fun leaveFoyer() {
        if (leaveState.value is LeaveState.Loading) return
        leaveState.value = LeaveState.Loading
        scope.launch {
            try {
                val foyerId = authRepository.foyerIdFlow.first()
                if (foyerId.isNullOrEmpty()) {
                    leaveState.value = LeaveState.Error(appContext.getString(R.string.settings_leave_no_active_foyer))
                    return@launch
                }
                val resp = apiService.leaveFoyer(foyerId)
                if (resp.code() == 404) {
                    // P4a.1 Bug B : 404 = foyer deja quitte/supprime cote
                    // serveur (re-leave d'un foyer fantome reste en local).
                    // On ne jette PAS d'erreur : purge locale immediate +
                    // reconciliation serveur (serveur autoritaire).
                    allFoyers.value =
                        allFoyers.value.filterNot { it.id == foyerId }
                    fetchFoyers()
                    if (allFoyers.value.isEmpty()) {
                        authRepository.logout()
                        leaveState.value = LeaveState.LoggedOut
                    } else {
                        leaveState.value = LeaveState.SwitchedFoyer
                    }
                    return@launch
                }
                if (!resp.isSuccessful) {
                    leaveState.value =
                        LeaveState.Error(appContext.getString(R.string.common_error_code_format, resp.code()))
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    leaveState.value = LeaveState.Error(appContext.getString(R.string.common_empty_response))
                    return@launch
                }
                // P4a.1 Bug B : retrait OPTIMISTE immediat du foyer quitte
                // -> il disparait de "Changer de foyer" sans refresh manuel.
                allFoyers.value =
                    allFoyers.value.filterNot { it.id == foyerId }
                if (body.newToken != null && body.newActiveFoyerId != null) {
                    // L'user est encore membre d'un autre foyer : on bascule
                    // dessus sans logout, puis on reconcilie la liste.
                    tokenManager.updateTokenAndFoyer(
                        body.newToken,
                        body.newActiveFoyerId,
                    )
                    fetchFoyers()
                    leaveState.value = LeaveState.SwitchedFoyer
                } else {
                    // Plus aucun foyer : logout complet.
                    authRepository.logout()
                    leaveState.value = LeaveState.LoggedOut
                }
            } catch (e: Exception) {
                leaveState.value =
                    LeaveState.Error(e.message ?: appContext.getString(R.string.common_error_network))
            }
        }
    }
}

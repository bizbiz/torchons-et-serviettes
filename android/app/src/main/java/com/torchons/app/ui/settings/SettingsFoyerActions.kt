package com.torchons.app.ui.settings

import android.content.Context
import android.util.Log
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Foyer
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.HouseholdSwitcher
import com.torchons.app.ui.common.OnlineResult
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.ui.common.runOnline
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
    private val switcher: HouseholdSwitcher,
    private val appContext: Context,
    private val currentFoyer: MutableStateFlow<Foyer?>,
    private val allFoyers: MutableStateFlow<List<Foyer>>,
    private val leaveState: MutableStateFlow<LeaveState>,
    private val switchState: MutableStateFlow<FoyerSwitchState>,
    private val loadAll: () -> Unit,
    /** Where a refused online-only write is told (the screen's action-error dialog). */
    private val onError: (UserError) -> Unit,
) {

    /**
     * Renomme le foyer courant (edition inline depuis l'ecran Foyer).
     * Reutilise PUT /api/foyers/{id} (deja utilise par FoyersScreen). Update
     * optimiste local pour reactivite UI, puis sync backend.
     */
    fun renameCurrentFoyer(newName: String) {
        val foyer = currentFoyer.value ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == foyer.name) return
        updateFoyer(foyer, mapOf("name" to trimmed)) { it.copy(name = trimmed) }
    }

    /**
     * PUT /api/foyers/{id} with [fields]; [apply] is the optimistic change, shown at once. A
     * refusal or a network failure puts the foyer back as it was and says so (D7, 2026-11-20
     * audit: the screen kept the new name / switch the server never took). The controls are
     * disabled offline, so this failure path is the first tap after the connection dropped.
     */
    private fun updateFoyer(foyer: Foyer, fields: Map<String, Any>, apply: (Foyer) -> Foyer) {
        replaceFoyer(foyer.id, apply)
        scope.launch {
            val result = runOnline(
                errorOf = { UserErrors.from(appContext, it) },
                rollback = { replaceFoyer(foyer.id) { foyer } },
            ) { apiService.updateFoyer(foyer.id, fields) }
            when (result) {
                is OnlineResult.Done -> result.body?.let { updated -> currentFoyer.value = updated }
                is OnlineResult.Failed -> onError(result.error)
            }
        }
    }

    private fun replaceFoyer(id: String, change: (Foyer) -> Foyer) {
        currentFoyer.value = currentFoyer.value?.takeIf { it.id == id }?.let(change) ?: currentFoyer.value
        allFoyers.value = allFoyers.value.map { if (it.id == id) change(it) else it }
    }

    /**
     * Toggle la mise en pause des taches "a la maison" quand tout le monde
     * est absent (home freeze) pour le foyer courant. Reutilise
     * PUT /api/foyers/{id} (champ home_freeze_enabled, deja gere backend).
     */
    fun setCurrentFoyerHomeFreezeEnabled(enabled: Boolean) {
        val foyer = currentFoyer.value ?: return
        updateFoyer(foyer, mapOf("home_freeze_enabled" to enabled)) { it.copy(homeFreezeEnabled = enabled) }
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

    /**
     * Token switch + wipe + refresh, all in the application scope ([HouseholdSwitcher]); this
     * screen only waits for the outcome, which the switch sheet shows ([switchState]).
     */
    private suspend fun doSwitchAndRefresh(foyerId: String) {
        val result = switcher.switchWith { authRepository.switchFoyer(foyerId) }
        if (result.isSuccess) {
            loadAll()
            switchState.value = FoyerSwitchState.Done
        } else {
            val failure = result.exceptionOrNull() ?: IllegalStateException("switch failed")
            switchState.value = FoyerSwitchState.Error(UserErrors.from(appContext, failure))
        }
    }

    /** Bascule vers un autre foyer existant (feuille "Changer de foyer"). */
    fun switchToFoyer(foyerId: String) {
        if (switchState.value is FoyerSwitchState.Switching) return
        switchState.value = FoyerSwitchState.Switching
        scope.launch { doSwitchAndRefresh(foyerId) }
    }

    /** The sheet was closed or its outcome read: back to idle (never while a switch is running). */
    fun dismissSwitch() {
        if (switchState.value !is FoyerSwitchState.Switching) switchState.value = FoyerSwitchState.Idle
    }

    /**
     * Cree un nouveau foyer puis bascule dessus (bouton "Creer un nouveau
     * foyer" des Reglages, design decision 3). Reutilise POST /api/foyers
     * (meme endpoint que l'ancien ecran Mes foyers — aucun endpoint invente).
     */
    fun createAndSwitchToFoyer(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (switchState.value is FoyerSwitchState.Switching) return
        switchState.value = FoyerSwitchState.Switching
        scope.launch {
            try {
                val resp = apiService.createFoyer(FoyerCreateRequest(trimmed))
                val created = if (resp.isSuccessful) resp.body() else null
                when {
                    created != null -> doSwitchAndRefresh(created.id)
                    resp.isSuccessful -> {
                        loadAll()
                        switchState.value = FoyerSwitchState.Done
                    }
                    else -> switchState.value = FoyerSwitchState.Error(UserErrors.fromResponse(appContext, resp))
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "createAndSwitchToFoyer failed", e)
                switchState.value = FoyerSwitchState.Error(UserErrors.from(appContext, e))
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
                    leaveState.value = LeaveState.Error(
                        UserError(appContext.getString(R.string.settings_leave_no_active_foyer)),
                    )
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
                    leaveState.value = LeaveState.Error(UserErrors.fromResponse(appContext, resp))
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    leaveState.value = LeaveState.Error(UserError(appContext.getString(R.string.common_empty_response)))
                    return@launch
                }
                // P4a.1 Bug B : retrait OPTIMISTE immediat du foyer quitte
                // -> il disparait de "Changer de foyer" sans refresh manuel.
                allFoyers.value =
                    allFoyers.value.filterNot { it.id == foyerId }
                if (body.newToken != null && body.newActiveFoyerId != null) {
                    // L'user est encore membre d'un autre foyer : on bascule
                    // dessus sans logout, puis on reconcilie la liste.
                    // The cache of the household left must not stay under the new token: same
                    // sequence as a switch (D5), in the application scope.
                    val switched = switcher.switchWith {
                        tokenManager.updateTokenAndFoyer(body.newToken, body.newActiveFoyerId)
                        Result.success(Unit)
                    }
                    fetchFoyers()
                    leaveState.value = switched.fold(
                        onSuccess = { LeaveState.SwitchedFoyer },
                        onFailure = { LeaveState.Error(UserErrors.from(appContext, it)) },
                    )
                } else {
                    // Plus aucun foyer : logout complet.
                    authRepository.logout()
                    leaveState.value = LeaveState.LoggedOut
                }
            } catch (e: Exception) {
                leaveState.value = LeaveState.Error(UserErrors.from(appContext, e))
            }
        }
    }
}

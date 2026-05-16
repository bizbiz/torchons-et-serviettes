package com.torchons.app.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BonusConfig
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.Foyer
import com.torchons.app.data.model.FoyerCreateRequest
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserSettingsState(
    val notifDaily: Boolean = true,
    val notifUrgent: Boolean = true,
    // Trois flags pour les completions, par defaut opt-in (false).
    val notifSolo: Boolean = false,
    val notifCollectiveWithMe: Boolean = false,
    val notifCollectiveWithoutMe: Boolean = false,
    val notifHour: Int = 9,
    val affinityPopup: Boolean = true,
    val affinityExpiry: Int = 6,
    val fairShareTolerance: Int = 30,
    val homeSsids: List<String> = emptyList(),
    // Nb de membres du foyer actif : utilise pour cacher le toggle
    // "without_me" quand <= 2.
    val foyerMemberCount: Int = 2,
    // v0.32 : toggle gamification user-level (global, s'applique a tous
    // les foyers de l'user). Combine en OR avec foyer-level cote UI.
    val gamificationEnabled: Boolean = true,
    val isLoaded: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    private val taskRepository: TaskRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _backendConnected = MutableStateFlow(false)
    val backendConnected: StateFlow<Boolean> = _backendConnected.asStateFlow()

    private val _settings = MutableStateFlow(UserSettingsState())
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    private val _fairShare = MutableStateFlow<List<FairShareStatus>>(emptyList())
    val fairShare: StateFlow<List<FairShareStatus>> = _fairShare.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _fairShareRatio = MutableStateFlow(60)
    val fairShareRatio: StateFlow<Int> = _fairShareRatio.asStateFlow()

    private val _bonusConfig = MutableStateFlow<BonusConfig?>(null)
    val bonusConfig: StateFlow<BonusConfig?> = _bonusConfig.asStateFlow()

    // v0.32 : foyer courant + nom (pour les labels des dialogs gamif).
    private val _currentFoyer = MutableStateFlow<Foyer?>(null)
    val currentFoyer: StateFlow<Foyer?> = _currentFoyer.asStateFlow()

    // v0.32 : tous les foyers de l'user (pour le dialog "Pourquoi pas de
    // points" en mode multi-foyer).
    private val _allFoyers = MutableStateFlow<List<Foyer>>(emptyList())
    val allFoyers: StateFlow<List<Foyer>> = _allFoyers.asStateFlow()

    init {
        loadAll()
        checkBackend()
    }

    fun checkBackend() {
        viewModelScope.launch {
            try {
                val resp = apiService.health()
                _backendConnected.value = resp.isSuccessful
            } catch (_: Exception) {
                _backendConnected.value = false
            }
        }
    }

    private fun loadAll() {
        viewModelScope.launch {
            try {
                // Settings from backend
                val resp = apiService.getSettings()
                if (resp.isSuccessful) {
                    val data = resp.body() ?: emptyMap()
                    _settings.value = UserSettingsState(
                        notifDaily = data["notif_daily"] as? Boolean ?: true,
                        notifUrgent = data["notif_urgent"] as? Boolean ?: true,
                        notifSolo = data["notif_solo"] as? Boolean ?: false,
                        notifCollectiveWithMe = data["notif_collective_with_me"] as? Boolean ?: false,
                        notifCollectiveWithoutMe = data["notif_collective_without_me"] as? Boolean ?: false,
                        notifHour = (data["notif_hour"] as? Double)?.toInt() ?: 9,
                        affinityPopup = data["affinity_popup_enabled"] as? Boolean ?: true,
                        affinityExpiry = (data["affinity_expiry_months"] as? Double)?.toInt() ?: 6,
                        fairShareTolerance = (data["fair_share_tolerance_percent"] as? Double)?.toInt() ?: 30,
                        homeSsids = (data["home_ssids"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        foyerMemberCount = (data["foyer_member_count"] as? Double)?.toInt() ?: 2,
                        gamificationEnabled = data["gamification_enabled"] as? Boolean ?: true,
                        isLoaded = true,
                    )
                    // Sync tous les reglages vers TokenManager local
                    syncToTokenManager(_settings.value)
                }

                // Fair-share
                val usersResp = apiService.getUsers()
                val meResp = apiService.getMe()
                val meId = if (meResp.isSuccessful) meResp.body()?.id else null
                if (usersResp.isSuccessful) {
                    // User connecte en premier pour le slider fair-share
                    _users.value = (usersResp.body() ?: emptyList()).sortedBy { if (it.id == meId) 0 else 1 }
                }
                val fsResp = apiService.getFairShareStatus()
                if (fsResp.isSuccessful) {
                    val data = fsResp.body() ?: emptyList()
                    _fairShare.value = data
                    // Le slider montre le ratio du user connecte (users[0] alphabetique)
                    // On trie toujours pareil pour coherence
                    val firstUser = _users.value.firstOrNull()
                    if (firstUser != null) {
                        val fs = data.find { it.userId == firstUser.id }
                        _fairShareRatio.value = ((fs?.targetRatio ?: 0.5) * 100).toInt()
                    }
                }
            } catch (_: Exception) {}

            // Config bonus/malus du foyer
            try {
                val bcResp = apiService.getBonusConfig()
                if (bcResp.isSuccessful) {
                    _bonusConfig.value = bcResp.body()
                }
            } catch (_: Exception) {}

            // v0.32 : liste des foyers de l'user + identification du foyer
            // courant (pour le toggle gamif foyer-level et le dialog
            // explicatif "Pourquoi pas de points"). Synchronise aussi le
            // flag local du foyer courant pour que tous les composants UI
            // qui observent gamificationVisibleFlow soient a jour.
            try {
                val fResp = apiService.getMyFoyers()
                if (fResp.isSuccessful) {
                    val foyers = fResp.body() ?: emptyList()
                    _allFoyers.value = foyers
                    val current = foyers.find { it.isCurrent }
                    _currentFoyer.value = current
                    if (current != null) {
                        tokenManager.setCurrentFoyerGamificationEnabled(current.gamificationEnabled)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateSetting(key: String, value: Any) {
        Log.d("SettingsVM", "updateSetting: $key = $value")
        viewModelScope.launch {
            try {
                val resp = apiService.updateSettings(mapOf(key to value))
                Log.d("SettingsVM", "updateSetting response: ${resp.code()} ${resp.isSuccessful}")
            } catch (e: Exception) {
                Log.e("SettingsVM", "updateSetting FAILED: $key", e)
            }
            syncToTokenManager(_settings.value)
        }
    }

    /**
     * Synchronise tous les reglages vers le TokenManager local (DataStore).
     * Necessaire pour que les services hors ViewModel (FCM Worker, widget,
     * filtres WiFi maison) aient les bonnes valeurs.
     */
    private suspend fun syncToTokenManager(s: UserSettingsState) {
        tokenManager.setNotifDaily(s.notifDaily)
        tokenManager.setNotifUrgent(s.notifUrgent)
        tokenManager.setNotifHour(s.notifHour)
        tokenManager.setAffinityPopup(s.affinityPopup)
        tokenManager.setAffinityExpiry(s.affinityExpiry)
        // home_ssids : remplacer entierement (clear + re-add)
        tokenManager.setHomeSsids(s.homeSsids)
        // v0.32 : flag gamification user-level. Resync local pour que tous
        // les composants UI qui observent gamificationVisibleFlow soient
        // a jour sans devoir recharger /api/settings.
        tokenManager.setUserGamificationEnabled(s.gamificationEnabled)
    }

    fun setNotifDaily(v: Boolean) {
        _settings.value = _settings.value.copy(notifDaily = v)
        updateSetting("notif_daily", v)
        rescheduleDailyNotif()
    }
    fun setNotifUrgent(v: Boolean) { _settings.value = _settings.value.copy(notifUrgent = v); updateSetting("notif_urgent", v) }
    fun setNotifSolo(v: Boolean) { _settings.value = _settings.value.copy(notifSolo = v); updateSetting("notif_solo", v) }
    fun setNotifCollectiveWithMe(v: Boolean) { _settings.value = _settings.value.copy(notifCollectiveWithMe = v); updateSetting("notif_collective_with_me", v) }
    fun setNotifCollectiveWithoutMe(v: Boolean) { _settings.value = _settings.value.copy(notifCollectiveWithoutMe = v); updateSetting("notif_collective_without_me", v) }
    fun setNotifHour(v: Int) {
        _settings.value = _settings.value.copy(notifHour = v)
        updateSetting("notif_hour", v)
        rescheduleDailyNotif()
    }

    /**
     * Reschedule le worker de notif quotidienne avec les valeurs courantes
     * (toggle + heure). replace=true pour ecraser l'instance existante
     * et appliquer le nouveau delai immediatement.
     */
    private fun rescheduleDailyNotif() {
        val s = _settings.value
        DailyNotificationWorker.schedule(
            context = appContext,
            hourOfDay = s.notifHour,
            enabled = s.notifDaily,
            replace = true,
        )
    }
    fun setAffinityPopup(v: Boolean) { _settings.value = _settings.value.copy(affinityPopup = v); updateSetting("affinity_popup_enabled", v) }

    /** v0.32 : toggle user-level. Effet immediat sur l'UI via TokenManager. */
    fun setGamificationEnabled(v: Boolean) {
        _settings.value = _settings.value.copy(gamificationEnabled = v)
        updateSetting("gamification_enabled", v)
    }

    /** v0.32 : toggle gamification du foyer courant. Tout membre peut
     *  toggler (cf grill-me Q4) — friction cote UI via dialog de
     *  confirmation mentionnant l'impact sur l'autre membre. */
    fun setCurrentFoyerGamificationEnabled(enabled: Boolean) {
        val foyer = _currentFoyer.value ?: return
        // Optimistic update : on flip le local d'abord pour reactivite UI.
        _currentFoyer.value = foyer.copy(gamificationEnabled = enabled)
        _allFoyers.value = _allFoyers.value.map {
            if (it.id == foyer.id) it.copy(gamificationEnabled = enabled) else it
        }
        viewModelScope.launch {
            tokenManager.setCurrentFoyerGamificationEnabled(enabled)
            try {
                val resp = apiService.updateFoyerGamification(
                    foyer.id,
                    mapOf("enabled" to enabled),
                )
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) {
                        _currentFoyer.value = updated
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
        val foyer = _currentFoyer.value ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == foyer.name) return
        _currentFoyer.value = foyer.copy(name = trimmed)
        _allFoyers.value = _allFoyers.value.map {
            if (it.id == foyer.id) it.copy(name = trimmed) else it
        }
        viewModelScope.launch {
            try {
                val resp = apiService.updateFoyer(foyer.id, mapOf("name" to trimmed))
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) _currentFoyer.value = updated
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
        val foyer = _currentFoyer.value ?: return
        _currentFoyer.value = foyer.copy(homeFreezeEnabled = enabled)
        _allFoyers.value = _allFoyers.value.map {
            if (it.id == foyer.id) it.copy(homeFreezeEnabled = enabled) else it
        }
        viewModelScope.launch {
            try {
                val resp = apiService.updateFoyer(
                    foyer.id,
                    mapOf("home_freeze_enabled" to enabled),
                )
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    if (updated != null) _currentFoyer.value = updated
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
    private suspend fun doSwitchAndRefresh(foyerId: String) {
        val result = authRepository.switchFoyer(foyerId)
        if (result.isSuccess) {
            taskRepository.wipeLocalCache()
            taskRepository.refreshTasks()
            taskRepository.refreshCategories()
            taskRepository.refreshRooms()
            taskRepository.refreshUsers()
            taskRepository.refreshCompletions(limit = 2000)
            loadAll()
        }
    }

    /** Bascule vers un autre foyer existant (feuille "Changer de foyer"). */
    fun switchToFoyer(foyerId: String) {
        viewModelScope.launch { doSwitchAndRefresh(foyerId) }
    }

    /**
     * Cree un nouveau foyer puis bascule dessus (bouton "Creer un nouveau
     * foyer" des Reglages, design decision 3). Reutilise POST /api/foyers
     * (meme endpoint que l'ancien ecran Mes foyers — aucun endpoint invente).
     */
    fun createAndSwitchToFoyer(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
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

    // --- Simulation rattrapage (reutilise ProjectionSimulationDialog) ---
    // Les memes endpoints que HomeViewModel.refreshProjection(). Le flow
    // "Proposer au foyer" (live FCM) reste pilote par HomeViewModel : ici
    // on supporte uniquement "Juste pour moi" (cf P1, design decision 7).

    private val _simPace = MutableStateFlow<PaceResponse?>(null)
    val simPace: StateFlow<PaceResponse?> = _simPace.asStateFlow()

    private val _simRetard = MutableStateFlow<RetardResponse?>(null)
    val simRetard: StateFlow<RetardResponse?> = _simRetard.asStateFlow()

    private val _simMaintenance = MutableStateFlow<MaintenanceRateResponse?>(null)
    val simMaintenance: StateFlow<MaintenanceRateResponse?> = _simMaintenance.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    /** Charge les 3 endpoints projection (pace/retard/maintenance) + l'id
     *  du user courant, pour alimenter ProjectionSimulationDialog. */
    fun loadSimulationData() {
        viewModelScope.launch {
            try {
                val meResp = apiService.getMe()
                if (meResp.isSuccessful) _currentUserId.value = meResp.body()?.id
            } catch (_: Exception) {}
            try {
                val r = apiService.getRetard()
                if (r.isSuccessful) _simRetard.value = r.body()
            } catch (_: Exception) {}
            try {
                val p = apiService.getPace()
                if (p.isSuccessful) _simPace.value = p.body()
            } catch (_: Exception) {}
            try {
                val m = apiService.getMaintenanceRate()
                if (m.isSuccessful) _simMaintenance.value = m.body()
            } catch (_: Exception) {}
        }
    }

    /** Sauve l'objectif perso de l'user courant (bouton "Juste pour moi"
     *  du dialog de simulation). Refresh les donnees apres. */
    fun saveObjective(minutes: Int?, unit: String?) {
        viewModelScope.launch {
            try {
                apiService.updateObjective(
                    ObjectiveUpdate(dailyObjectiveMinutes = minutes, displayUnit = unit),
                )
                loadSimulationData()
            } catch (e: Exception) {
                Log.e("SettingsVM", "saveObjective failed", e)
            }
        }
    }

    fun setAffinityExpiry(v: Int) { _settings.value = _settings.value.copy(affinityExpiry = v); updateSetting("affinity_expiry_months", v) }
    fun setFairShareTolerance(v: Int) { _settings.value = _settings.value.copy(fairShareTolerance = v); updateSetting("fair_share_tolerance_percent", v) }

    fun addHomeSsid(ssid: String) {
        val updated = _settings.value.homeSsids + ssid
        _settings.value = _settings.value.copy(homeSsids = updated)
        updateSetting("home_ssids", updated)
    }
    fun removeHomeSsid(ssid: String) {
        val updated = _settings.value.homeSsids.filter { it != ssid }
        _settings.value = _settings.value.copy(homeSsids = updated)
        updateSetting("home_ssids", updated)
    }

    fun updateFairShareRatio(ratio: Int) { _fairShareRatio.value = ratio }
    fun saveFairShare() {
        viewModelScope.launch {
            val users = _users.value
            if (users.size < 2) return@launch
            val ratio = _fairShareRatio.value / 100.0
            try {
                apiService.updateFairShareConfig(listOf(
                    mapOf("user_id" to users[0].id, "target_ratio" to ratio),
                    mapOf("user_id" to users[1].id, "target_ratio" to (1.0 - ratio))
                ))
            } catch (_: Exception) {}
        }
    }

    /**
     * Met a jour un parametre de la config bonus/malus du foyer.
     * Mise a jour partielle — seul le champ specifie est modifie.
     */
    fun updateBonusConfig(key: String, value: Any) {
        viewModelScope.launch {
            try {
                val resp = apiService.updateBonusConfig(mapOf(key to value))
                if (resp.isSuccessful) {
                    _bonusConfig.value = resp.body()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun logout() { authRepository.logout() }

    // --- v0.30 : actions onboarding (regenerer code, quitter foyer) ---

    /**
     * Etat du flow "Regenerer mon code de recuperation".
     * Idle -> Loading -> Success(code) ou Error.
     */
    sealed class RegenerateState {
        data object Idle : RegenerateState()
        data object Loading : RegenerateState()
        data class Success(val recoveryCode: String) : RegenerateState()
        data class Error(val message: String) : RegenerateState()
    }

    private val _regenerateState = MutableStateFlow<RegenerateState>(RegenerateState.Idle)
    val regenerateState: StateFlow<RegenerateState> = _regenerateState.asStateFlow()

    fun regenerateRecoveryCode() {
        if (_regenerateState.value is RegenerateState.Loading) return
        _regenerateState.value = RegenerateState.Loading
        viewModelScope.launch {
            try {
                val resp = apiService.recoveryRegenerate()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) {
                        _regenerateState.value = RegenerateState.Success(body.recoveryCode)
                    } else {
                        _regenerateState.value = RegenerateState.Error("Reponse vide")
                    }
                } else {
                    _regenerateState.value =
                        RegenerateState.Error("Erreur ${resp.code()}")
                }
            } catch (e: Exception) {
                _regenerateState.value =
                    RegenerateState.Error(e.message ?: "Erreur reseau")
            }
        }
    }

    fun dismissRegenerate() {
        _regenerateState.value = RegenerateState.Idle
    }

    /**
     * Etat du flow "Quitter le foyer".
     * Idle -> Loading -> SwitchedFoyer (reste connecte sur un autre foyer)
     *                ou LoggedOut (plus aucun foyer, on doit retourner a Welcome)
     *                ou Error.
     */
    sealed class LeaveState {
        data object Idle : LeaveState()
        data object Loading : LeaveState()
        data object SwitchedFoyer : LeaveState()
        data object LoggedOut : LeaveState()
        data class Error(val message: String) : LeaveState()
    }

    private val _leaveState = MutableStateFlow<LeaveState>(LeaveState.Idle)
    val leaveState: StateFlow<LeaveState> = _leaveState.asStateFlow()

    fun leaveFoyer() {
        if (_leaveState.value is LeaveState.Loading) return
        _leaveState.value = LeaveState.Loading
        viewModelScope.launch {
            try {
                val foyerId = authRepository.foyerIdFlow.first()
                if (foyerId.isNullOrEmpty()) {
                    _leaveState.value = LeaveState.Error("Aucun foyer actif")
                    return@launch
                }
                val resp = apiService.leaveFoyer(foyerId)
                if (!resp.isSuccessful) {
                    _leaveState.value = LeaveState.Error("Erreur ${resp.code()}")
                    return@launch
                }
                val body = resp.body()
                if (body == null) {
                    _leaveState.value = LeaveState.Error("Reponse vide")
                    return@launch
                }
                if (body.newToken != null && body.newActiveFoyerId != null) {
                    // L'user est encore membre d'un autre foyer : on bascule
                    // dessus sans logout.
                    tokenManager.updateTokenAndFoyer(
                        body.newToken,
                        body.newActiveFoyerId,
                    )
                    _leaveState.value = LeaveState.SwitchedFoyer
                } else {
                    // Plus aucun foyer : logout complet.
                    authRepository.logout()
                    _leaveState.value = LeaveState.LoggedOut
                }
            } catch (e: Exception) {
                _leaveState.value =
                    LeaveState.Error(e.message ?: "Erreur reseau")
            }
        }
    }

    fun dismissLeave() {
        _leaveState.value = LeaveState.Idle
    }
}

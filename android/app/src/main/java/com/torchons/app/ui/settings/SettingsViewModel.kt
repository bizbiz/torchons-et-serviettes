package com.torchons.app.ui.settings

import com.torchons.app.R
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.DeviceRevocationMonitor
import com.torchons.app.data.api.ApiService
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BonusConfig
import com.torchons.app.data.model.Foyer
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.ModuleToggleRequest
import com.torchons.app.data.model.ModuleView
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.core.proposition.PropositionStateHolder
import com.torchons.app.worker.DailyNotificationWorker
import com.torchons.app.worker.OverdueNotificationWorker
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    private val taskRepository: TaskRepository,
    private val propositionStateHolder: PropositionStateHolder,
    private val revocationMonitor: DeviceRevocationMonitor,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** "Propose to household" from the Foyer screen: same live flow as the home screen. */
    fun proposeToFoyer(sliderValues: Map<String, Int>, unit: String) =
        propositionStateHolder.propose(sliderValues, unit)

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // P4b : id du user courant — defaut du selecteur du modal "Présence
    // & séries" ouvert depuis l'ecran Foyer.
    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _backendConnected = MutableStateFlow(false)
    val backendConnected: StateFlow<Boolean> = _backendConnected.asStateFlow()

    private val _settings = MutableStateFlow(UserSettingsState())
    val settings: StateFlow<UserSettingsState> = _settings.asStateFlow()

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _bonusConfig = MutableStateFlow<BonusConfig?>(null)
    val bonusConfig: StateFlow<BonusConfig?> = _bonusConfig.asStateFlow()

    // P2 — registre des modules (GET /api/modules), vide tant que la reponse
    // n'est pas arrivee (l'ecran montre alors le chargement).
    private val _modules = MutableStateFlow<List<ModuleView>>(emptyList())
    val modules: StateFlow<List<ModuleView>> = _modules.asStateFlow()

    // True while loadAll() runs. Drives the modules spinner: it must stop once
    // an attempt has failed (offline), so the "unavailable" message can show.
    private val _loadingAll = MutableStateFlow(true)
    val loadingAll: StateFlow<Boolean> = _loadingAll.asStateFlow()

    // Message d'erreur transitoire d'une bascule refusee (409 dependance).
    private val _moduleError = MutableStateFlow<UserError?>(null)
    val moduleError: StateFlow<UserError?> = _moduleError.asStateFlow()

    private val _currentFoyer = MutableStateFlow<Foyer?>(null)
    val currentFoyer: StateFlow<Foyer?> = _currentFoyer.asStateFlow()

    // v0.32 : tous les foyers de l'user (pour le dialog "Pourquoi pas de
    // points" en mode multi-foyer).
    private val _allFoyers = MutableStateFlow<List<Foyer>>(emptyList())
    val allFoyers: StateFlow<List<Foyer>> = _allFoyers.asStateFlow()

    init {
        seedFoyerFromCache()
        loadAll()
        checkBackend()
    }

    /** P1 sweep : seed le foyer courant depuis le cache local (DataStore)
     *  AVANT que /api/foyers reponde, pour ne plus flasher le placeholder
     *  "Mon foyer" a l'ouverture de l'ecran Foyer. loadAll() remplace ensuite
     *  par la donnee serveur autoritaire (online = serveur autoritaire,
     *  cf docs/cache-conventions.md). */
    private fun seedFoyerFromCache() {
        viewModelScope.launch {
            if (_currentFoyer.value != null) return@launch
            val id = tokenManager.getCachedFoyerId()
            val name = tokenManager.getCachedFoyerName()
            if (id != null && name != null && _currentFoyer.value == null) {
                _currentFoyer.value = Foyer(id = id, name = name)
            }
        }
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

    /**
     * Reloads when the screen comes back and the first load left gaps. The
     * bottom tabs keep this ViewModel alive (saveState), so a screen first
     * opened offline would otherwise never retry once the network is back.
     */
    fun refreshIfIncomplete() {
        if (_loadingAll.value) return
        if (!_settings.value.isLoaded || _modules.value.isEmpty()) loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _loadingAll.value = true
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
                        notifOverdueHour = (data["notif_overdue_hour"] as? Number)?.toInt() ?: 8,
                        affinityPopup = data["affinity_popup_enabled"] as? Boolean ?: true,
                        affinityExpiry = (data["affinity_expiry_months"] as? Number)?.toInt()
                            ?: TokenManager.DEFAULT_AFFINITY_EXPIRY_MONTHS,
                        fairShareTolerance = (data["fair_share_tolerance_percent"] as? Double)?.toInt() ?: 30,
                        homeSsids = (data["home_ssids"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        foyerMemberCount = (data["foyer_member_count"] as? Double)?.toInt() ?: 2,
                        gamificationEnabled = data["gamification_enabled"] as? Boolean ?: true,
                        hideHomeTasksOffWifi = data["hide_home_tasks_off_wifi"] as? Boolean ?: true,
                        notifTodayReminders = data["notif_today_reminders"] as? Boolean ?: true,
                        isLoaded = true,
                    )
                    // Sync tous les reglages vers TokenManager local
                    syncToTokenManager(_settings.value)
                }

                // Modules — le chargement pousse aussi l'etat dans
                // TokenManager, qui est ce que lisent les autres ecrans.
                loadModules()

                // Fair-share
                val usersResp = apiService.getUsers()
                val meResp = apiService.getMe()
                val meId = if (meResp.isSuccessful) meResp.body()?.id else null
                if (usersResp.isSuccessful) {
                    // User connecte en premier pour le slider fair-share
                    _users.value = (usersResp.body() ?: emptyList()).sortedBy { if (it.id == meId) 0 else 1 }
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
                    // P4a.1 sweep — exclure les foyers quittés (role
                    // "former_member") : "Changer de foyer" ne liste que
                    // les foyers dont je suis membre ACTIF. /foyers les
                    // renvoie encore (pas de filtre left_at serveur).
                    _allFoyers.value =
                        foyers.filter { it.role != "former_member" }
                    val current = foyers.find { it.isCurrent }
                    _currentFoyer.value = current
                    if (current != null) {
                        tokenManager.setCurrentFoyerGamificationEnabled(current.gamificationEnabled)
                        // P1 sweep : persiste le nom pour le seed offline au
                        // prochain demarrage (cf seedFoyerFromCache).
                        tokenManager.setCurrentFoyerName(current.name)
                    }
                }
            } catch (_: Exception) {}
            // Every step above catches its own failures, so this is reached
            // on success and offline alike.
            _loadingAll.value = false
        }
    }

    private fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            try {
                val resp = apiService.updateSettings(mapOf(key to value))
                if (!resp.isSuccessful) Log.w("SettingsVM", "updateSetting $key rejected: ${resp.code()}")
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
        tokenManager.setNotifOverdueHour(s.notifOverdueHour)
        tokenManager.setAffinityPopup(s.affinityPopup)
        tokenManager.setAffinityExpiry(s.affinityExpiry)
        // home_ssids : remplacer entierement (clear + re-add)
        tokenManager.setHomeSsids(s.homeSsids)
        // v0.32 : flag gamification user-level. Resync local pour que tous
        // les composants UI qui observent gamificationVisibleFlow soient
        // a jour sans devoir recharger /api/settings.
        tokenManager.setUserGamificationEnabled(s.gamificationEnabled)
        // Masquer taches maison hors WiFi : lu par TaskListViewModel via
        // tokenManager.hideHomeTasksOffWifiFlow (memes raisons que homeSsids).
        tokenManager.setHideHomeTasksOffWifi(s.hideHomeTasksOffWifi)
    }

    fun setNotifDaily(v: Boolean) {
        _settings.value = _settings.value.copy(notifDaily = v)
        updateSetting("notif_daily", v)
        rescheduleDailyNotif()
    }
    // --- Modules (P2) ---

    /** Settings and Foyer each hold their own instance: a module switched on the Foyer
     *  screen is only seen here after a reload, so the screen reloads on every display. */
    fun refreshModules() {
        viewModelScope.launch { loadModules() }
    }

    /** Charge le registre et publie l'etat dans TokenManager. */
    private suspend fun loadModules() {
        try {
            val resp = apiService.getModules()
            if (resp.isSuccessful) {
                val list = resp.body()?.modules.orEmpty()
                _modules.value = list
                tokenManager.setModuleStates(list.associate { it.key to it.enabled })
            }
        } catch (e: Exception) {
            Log.w("SettingsViewModel", "loadModules: ${e.message}")
        }
    }

    /**
     * Bascule un module. Ecriture optimiste puis reconciliation sur la
     * reponse : un 409 (dependance non satisfaite) doit remettre
     * l'interrupteur ou il etait, sinon l'ecran affiche un etat que le
     * serveur a refuse.
     */
    fun toggleModule(key: String, enabled: Boolean, onEnabled: (ModuleView) -> Unit = {}) {
        val before = _modules.value
        _modules.value = before.map { if (it.key == key) it.copy(enabled = enabled) else it }
        viewModelScope.launch {
            try {
                val resp = apiService.toggleModule(key, ModuleToggleRequest(enabled))
                if (resp.isSuccessful) {
                    loadModules() // the server may have changed dependants too
                    // Manual enable confirmed by the server: hand the fresh module over.
                    if (enabled) _modules.value.firstOrNull { it.key == key }?.let(onEnabled)
                } else {
                    _modules.value = before
                    // A 400/409 body is JSON localised by the server
                    // ({"detail": "Activez d'abord : motivation"}): that sentence
                    // is the readable message. Any other refusal (5xx: the detail
                    // is a technical key) goes through the generic translator.
                    _moduleError.value = UserErrors.fromResponse(appContext, resp) {
                        it.detail?.takeIf { d -> d.isNotBlank() && it.httpCode in listOf(400, 409) }
                    }
                    Log.w("SettingsViewModel", "toggleModule $key refuse: ${resp.code()}")
                }
            } catch (e: Exception) {
                _modules.value = before
                _moduleError.value = UserErrors.from(appContext, e)
            }
        }
    }

    fun dismissModuleError() { _moduleError.value = null }

    fun setNotifUrgent(v: Boolean) {
        _settings.value = _settings.value.copy(notifUrgent = v)
        updateSetting("notif_urgent", v)
        rescheduleOverdueNotif()
    }
    fun setNotifOverdueHour(v: Int) {
        _settings.value = _settings.value.copy(notifOverdueHour = v)
        updateSetting("notif_overdue_hour", v)
        rescheduleOverdueNotif()
    }

    /** Re-plans the overdue notification from the toggle and its hour (replace: moves the next run). */
    private fun rescheduleOverdueNotif() {
        val s = _settings.value
        OverdueNotificationWorker.schedule(
            context = appContext,
            hourOfDay = s.notifOverdueHour,
            enabled = s.notifUrgent,
            replace = true,
        )
    }
    fun setNotifSolo(v: Boolean) { _settings.value = _settings.value.copy(notifSolo = v); updateSetting("notif_solo", v) }
    fun setNotifCollectiveWithMe(v: Boolean) { _settings.value = _settings.value.copy(notifCollectiveWithMe = v); updateSetting("notif_collective_with_me", v) }
    fun setNotifCollectiveWithoutMe(v: Boolean) { _settings.value = _settings.value.copy(notifCollectiveWithoutMe = v); updateSetting("notif_collective_without_me", v) }
    fun setNotifTodayReminders(v: Boolean) {
        _settings.value = _settings.value.copy(notifTodayReminders = v)
        updateSetting("notif_today_reminders", v)
    }
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

    /** Toggle "masquer taches maison hors WiFi" (user-level). updateSetting
     *  resync vers TokenManager -> TaskListViewModel recalcule les filtres. */
    fun setHideHomeTasksOffWifi(v: Boolean) {
        _settings.value = _settings.value.copy(hideHomeTasksOffWifi = v)
        updateSetting("hide_home_tasks_off_wifi", v)
    }

    // Foyer actions (rename, home freeze, switch, create): SettingsFoyerActions.
    fun renameCurrentFoyer(newName: String) = foyers.renameCurrentFoyer(newName)

    fun setCurrentFoyerHomeFreezeEnabled(enabled: Boolean) =
        foyers.setCurrentFoyerHomeFreezeEnabled(enabled)

    fun refreshFoyers() = foyers.refreshFoyers()

    /** Bascule vers un autre foyer existant (feuille "Changer de foyer"). */
    fun switchToFoyer(foyerId: String) = foyers.switchToFoyer(foyerId)

    fun createAndSwitchToFoyer(name: String) = foyers.createAndSwitchToFoyer(name)

    // Simulation rattrapage (ProjectionSimulationDialog) : SettingsSimulation.
    private val simulation = SettingsSimulation(viewModelScope, apiService)

    val simPace: StateFlow<PaceResponse?> = simulation.simPace

    val simRetard: StateFlow<RetardResponse?> = simulation.simRetard

    val simMaintenance: StateFlow<MaintenanceRateResponse?> = simulation.simMaintenance

    val currentUserId: StateFlow<String?> = simulation.currentUserId

    fun loadSimulationData() = simulation.loadSimulationData()

    fun saveObjective(minutes: Int?, unit: String?) = simulation.saveObjective(minutes, unit)

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
        data class Error(val error: UserError) : RegenerateState()
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
                        _regenerateState.value =
                            RegenerateState.Error(UserError(appContext.getString(R.string.common_empty_response)))
                    }
                } else {
                    _regenerateState.value = RegenerateState.Error(UserErrors.fromResponse(appContext, resp))
                }
            } catch (e: Exception) {
                _regenerateState.value = RegenerateState.Error(UserErrors.from(appContext, e))
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
        data class Error(val error: UserError) : LeaveState()
    }

    private val _leaveState = MutableStateFlow<LeaveState>(LeaveState.Idle)
    val leaveState: StateFlow<LeaveState> = _leaveState.asStateFlow()

    private val foyers = SettingsFoyerActions(
        scope = viewModelScope,
        apiService = apiService,
        tokenManager = tokenManager,
        authRepository = authRepository,
        taskRepository = taskRepository,
        appContext = appContext,
        currentFoyer = _currentFoyer,
        allFoyers = _allFoyers,
        leaveState = _leaveState,
        loadAll = ::loadAll,
    )

    fun leaveFoyer() = foyers.leaveFoyer()

    // Account deletion (DELETE /api/me), then the same local wipe as a revoked device.
    private val deleteAccountAction = DeleteAccountAction(
        scope = viewModelScope,
        deleteMe = apiService::deleteMe,
        wipe = revocationMonitor::wipeNow,
    )
    val deleteAccountState: StateFlow<DeleteAccountState> = deleteAccountAction.state

    fun deleteAccount() = deleteAccountAction.start()

    fun dismissDeleteAccountError() = deleteAccountAction.dismissError()

    fun dismissLeave() {
        _leaveState.value = LeaveState.Idle
    }
}

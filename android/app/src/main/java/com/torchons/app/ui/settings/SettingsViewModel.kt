package com.torchons.app.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BonusConfig
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserSettingsState(
    val notifDaily: Boolean = true,
    val notifUrgent: Boolean = true,
    val notifCompleted: Boolean = false,
    val notifHour: Int = 9,
    val affinityPopup: Boolean = true,
    val affinityExpiry: Int = 6,
    val fairShareTolerance: Int = 30,
    val homeSsids: List<String> = emptyList(),
    val isLoaded: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
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
                        notifCompleted = data["notif_completed"] as? Boolean ?: false,
                        notifHour = (data["notif_hour"] as? Double)?.toInt() ?: 9,
                        affinityPopup = data["affinity_popup_enabled"] as? Boolean ?: true,
                        affinityExpiry = (data["affinity_expiry_months"] as? Double)?.toInt() ?: 6,
                        fairShareTolerance = (data["fair_share_tolerance_percent"] as? Double)?.toInt() ?: 30,
                        homeSsids = (data["home_ssids"] as? List<*>)?.map { it.toString() } ?: emptyList(),
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
        tokenManager.setNotifCompleted(s.notifCompleted)
        tokenManager.setNotifHour(s.notifHour)
        tokenManager.setAffinityPopup(s.affinityPopup)
        tokenManager.setAffinityExpiry(s.affinityExpiry)
        // home_ssids : remplacer entierement (clear + re-add)
        tokenManager.setHomeSsids(s.homeSsids)
    }

    fun setNotifDaily(v: Boolean) {
        _settings.value = _settings.value.copy(notifDaily = v)
        updateSetting("notif_daily", v)
        rescheduleDailyNotif()
    }
    fun setNotifUrgent(v: Boolean) { _settings.value = _settings.value.copy(notifUrgent = v); updateSetting("notif_urgent", v) }
    fun setNotifCompleted(v: Boolean) { _settings.value = _settings.value.copy(notifCompleted = v); updateSetting("notif_completed", v) }
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
}

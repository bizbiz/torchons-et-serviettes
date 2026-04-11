package com.torchons.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val freshnessThreshold: Int = 10,
    val fairShareTolerance: Int = 30,
    val homeSsids: List<String> = emptyList(),
    val isLoaded: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService
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
                        freshnessThreshold = (data["freshness_threshold"] as? Double)?.toInt() ?: 10,
                        fairShareTolerance = (data["fair_share_tolerance_percent"] as? Double)?.toInt() ?: 30,
                        homeSsids = (data["home_ssids"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        isLoaded = true,
                    )
                    // Sync notif_completed to SharedPreferences for FCM service
                    tokenManager.setNotifCompleted(_settings.value.notifCompleted)
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
        }
    }

    private fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            try {
                apiService.updateSettings(mapOf(key to value))
            } catch (_: Exception) {}
        }
    }

    fun setNotifDaily(v: Boolean) { _settings.value = _settings.value.copy(notifDaily = v); updateSetting("notif_daily", v) }
    fun setNotifUrgent(v: Boolean) { _settings.value = _settings.value.copy(notifUrgent = v); updateSetting("notif_urgent", v) }
    fun setNotifCompleted(v: Boolean) {
        _settings.value = _settings.value.copy(notifCompleted = v)
        updateSetting("notif_completed", v)
        viewModelScope.launch { tokenManager.setNotifCompleted(v) }
    }
    fun setNotifHour(v: Int) { _settings.value = _settings.value.copy(notifHour = v); updateSetting("notif_hour", v) }
    fun setAffinityPopup(v: Boolean) { _settings.value = _settings.value.copy(affinityPopup = v); updateSetting("affinity_popup_enabled", v) }
    fun setAffinityExpiry(v: Int) { _settings.value = _settings.value.copy(affinityExpiry = v); updateSetting("affinity_expiry_months", v) }
    fun setFreshnessThreshold(v: Int) { _settings.value = _settings.value.copy(freshnessThreshold = v); updateSetting("freshness_threshold", v) }
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

    suspend fun logout() { authRepository.logout() }
}

package com.torchons.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.UserStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val stats: List<UserStats> = emptyList(),
    val leaderboardWeek: List<LeaderboardEntry> = emptyList(),
    val leaderboardMonth: List<LeaderboardEntry> = emptyList(),
    val streaks: List<StreakInfo> = emptyList(),
    val badges: List<BadgeInfo> = emptyList(),
    val history: List<Map<String, Any>> = emptyList(),
    val fairShare: List<FairShareStatus> = emptyList(),
    val selectedTab: Int = 0,
    val error: String? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val apiService: ApiService,
    tokenManager: TokenManager,
) : ViewModel() {

    val userId: StateFlow<String?> = tokenManager.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiState = MutableStateFlow(StatsUiState(isLoading = true))
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !isRefresh,
                isRefreshing = isRefresh
            )
            try {
                val statsResp = apiService.getStats()
                val weekResp = apiService.getLeaderboard("week")
                val monthResp = apiService.getLeaderboard("month")
                val streaksResp = apiService.getStreaks()
                val badgesResp = apiService.getBadges()
                val historyResp = apiService.getHistory()
                val fairShareResp = apiService.getFairShareStatus()

                _uiState.value = StatsUiState(
                    isLoading = false,
                    isRefreshing = false,
                    stats = if (statsResp.isSuccessful) statsResp.body() ?: emptyList() else emptyList(),
                    leaderboardWeek = if (weekResp.isSuccessful) weekResp.body() ?: emptyList() else emptyList(),
                    leaderboardMonth = if (monthResp.isSuccessful) monthResp.body() ?: emptyList() else emptyList(),
                    streaks = if (streaksResp.isSuccessful) streaksResp.body() ?: emptyList() else emptyList(),
                    badges = if (badgesResp.isSuccessful) badgesResp.body() ?: emptyList() else emptyList(),
                    history = if (historyResp.isSuccessful) historyResp.body() ?: emptyList() else emptyList(),
                    fairShare = if (fairShareResp.isSuccessful) fairShareResp.body() ?: emptyList() else emptyList(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }
}

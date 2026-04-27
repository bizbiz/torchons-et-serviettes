package com.torchons.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.PointsTimelineResponse
import com.torchons.app.data.model.RetardTimelineResponse
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.UserStats
import com.torchons.app.data.model.WeekdayFrequencyResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Periode preset pour le filtre de l'historique. Mappe vers une borne `since`. */
enum class HistoryPeriod { ALL, WEEK, MONTH, THREE_MONTHS }

/**
 * Etat des filtres applicables a l'historique. `families` est multi-select
 * (3 chips : completions / votes / modifications) ; `actorId` null = tous.
 */
data class HistoryFilters(
    val families: Set<String> = emptySet(),     // sous-ensemble de {completions,votes,modifications}
    val actorId: String? = null,
    val period: HistoryPeriod = HistoryPeriod.ALL,
    val query: String = "",
) {
    /** True si au moins un filtre est actif (sert a afficher un badge). */
    val hasActiveFilters: Boolean
        get() = families.isNotEmpty() || actorId != null || period != HistoryPeriod.ALL
}

data class StatsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val stats: List<UserStats> = emptyList(),
    val leaderboardWeek: List<LeaderboardEntry> = emptyList(),
    val leaderboardMonth: List<LeaderboardEntry> = emptyList(),
    val leaderboardAll: List<LeaderboardEntry> = emptyList(),
    val streaks: List<StreakInfo> = emptyList(),
    val badges: List<BadgeInfo> = emptyList(),
    val history: List<Map<String, Any>> = emptyList(),
    val historyFilters: HistoryFilters = HistoryFilters(),
    val isHistoryLoading: Boolean = false,
    val fairShare: List<FairShareStatus> = emptyList(),
    // Donnees graphiques (chargees en parallele du reste).
    val pointsWeek: PointsTimelineResponse? = null,
    val pointsMonth: PointsTimelineResponse? = null,
    val pointsAll: PointsTimelineResponse? = null,
    val weekdayFrequency: WeekdayFrequencyResponse? = null,
    val retardTimeline: RetardTimelineResponse? = null,
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
                val allResp = apiService.getLeaderboard("all")
                val streaksResp = apiService.getStreaks()
                val badgesResp = apiService.getBadges()
                val historyResp = apiService.getHistory()
                val fairShareResp = apiService.getFairShareStatus()
                val ptsWeekResp = apiService.getPointsTimeline("week")
                val ptsMonthResp = apiService.getPointsTimeline("month")
                val ptsAllResp = apiService.getPointsTimeline("all")
                val weekdayResp = apiService.getWeekdayFrequency()
                val retardResp = apiService.getRetardTimeline()

                _uiState.value = StatsUiState(
                    isLoading = false,
                    isRefreshing = false,
                    stats = if (statsResp.isSuccessful) statsResp.body() ?: emptyList() else emptyList(),
                    leaderboardWeek = if (weekResp.isSuccessful) weekResp.body() ?: emptyList() else emptyList(),
                    leaderboardMonth = if (monthResp.isSuccessful) monthResp.body() ?: emptyList() else emptyList(),
                    leaderboardAll = if (allResp.isSuccessful) allResp.body() ?: emptyList() else emptyList(),
                    streaks = if (streaksResp.isSuccessful) streaksResp.body() ?: emptyList() else emptyList(),
                    badges = if (badgesResp.isSuccessful) badgesResp.body() ?: emptyList() else emptyList(),
                    history = if (historyResp.isSuccessful) historyResp.body() ?: emptyList() else emptyList(),
                    fairShare = if (fairShareResp.isSuccessful) fairShareResp.body() ?: emptyList() else emptyList(),
                    pointsWeek = if (ptsWeekResp.isSuccessful) ptsWeekResp.body() else null,
                    pointsMonth = if (ptsMonthResp.isSuccessful) ptsMonthResp.body() else null,
                    pointsAll = if (ptsAllResp.isSuccessful) ptsAllResp.body() else null,
                    weekdayFrequency = if (weekdayResp.isSuccessful) weekdayResp.body() else null,
                    retardTimeline = if (retardResp.isSuccessful) retardResp.body() else null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    // --- Filtres de l'historique ---

    fun updateHistoryQuery(value: String) {
        val newFilters = _uiState.value.historyFilters.copy(query = value)
        _uiState.value = _uiState.value.copy(historyFilters = newFilters)
        refetchHistory(newFilters)
    }

    fun toggleHistoryFamily(family: String) {
        val current = _uiState.value.historyFilters.families
        val next = if (current.contains(family)) current - family else current + family
        val newFilters = _uiState.value.historyFilters.copy(families = next)
        _uiState.value = _uiState.value.copy(historyFilters = newFilters)
        refetchHistory(newFilters)
    }

    fun setHistoryActor(actorId: String?) {
        val newFilters = _uiState.value.historyFilters.copy(actorId = actorId)
        _uiState.value = _uiState.value.copy(historyFilters = newFilters)
        refetchHistory(newFilters)
    }

    fun setHistoryPeriod(period: HistoryPeriod) {
        val newFilters = _uiState.value.historyFilters.copy(period = period)
        _uiState.value = _uiState.value.copy(historyFilters = newFilters)
        refetchHistory(newFilters)
    }

    fun clearHistoryFilters() {
        val cleared = HistoryFilters()
        _uiState.value = _uiState.value.copy(historyFilters = cleared)
        refetchHistory(cleared)
    }

    /** Re-appelle GET /history avec les filtres courants. Debouncing simple :
     * une nouvelle requete remplace l'ancienne (la derniere arrivee gagne). */
    private fun refetchHistory(filters: HistoryFilters) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isHistoryLoading = true)
            val sinceIso = when (filters.period) {
                HistoryPeriod.ALL -> null
                HistoryPeriod.WEEK -> isoMinusDays(7)
                HistoryPeriod.MONTH -> isoMinusDays(30)
                HistoryPeriod.THREE_MONTHS -> isoMinusDays(90)
            }
            try {
                val resp = apiService.getHistory(
                    limit = 50,
                    before = null,
                    since = sinceIso,
                    families = filters.families.toList(),
                    actorId = filters.actorId,
                    q = filters.query.ifBlank { null },
                )
                val body = if (resp.isSuccessful) resp.body() ?: emptyList() else emptyList()
                _uiState.value = _uiState.value.copy(history = body, isHistoryLoading = false)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isHistoryLoading = false)
            }
        }
    }

    private fun isoMinusDays(days: Long): String {
        return java.time.OffsetDateTime
            .now(java.time.ZoneOffset.UTC)
            .minusDays(days)
            .toString()
    }
}

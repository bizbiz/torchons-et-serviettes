package com.torchons.app.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BadgeInfo
import com.torchons.app.data.model.CategoryFrequencyResponse
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Periode preset pour le filtre de l'historique. Mappe vers une borne `since`. */
enum class HistoryPeriod { ALL, WEEK, MONTH, THREE_MONTHS }

/** Periode du chart Weekday/Category — controle local au chart. */
enum class WeekdayPeriod(val apiValue: String) {
    WEEK("week"), MONTH("month"), THREE_MONTHS("three_months"), ALL("all"),
}

/** Periode du chart Retard — toggle binaire (depuis le debut OU 30 jours). */
enum class RetardPeriod(val apiValue: String) {
    ALL("all"), MONTH("month"),
}

/**
 * Etat des filtres applicables a l'historique. `families` est multi-select
 * (3 chips : completions / votes / modifications) ; `actorId` null = tous.
 *
 * `dayFilter` : ISO date YYYY-MM-DD. Quand non null, prend le pas sur
 * `period` et restreint l'historique a une journee precise (utilise par le
 * tap-on-point des charts qui deeplinke vers l'onglet Historique).
 */
data class HistoryFilters(
    val families: Set<String> = emptySet(),     // sous-ensemble de {completions,votes,modifications}
    val actorId: String? = null,
    val period: HistoryPeriod = HistoryPeriod.ALL,
    val query: String = "",
    val dayFilter: String? = null,
) {
    /** True si au moins un filtre est actif (sert a afficher un badge). */
    val hasActiveFilters: Boolean
        get() = families.isNotEmpty() || actorId != null
                || period != HistoryPeriod.ALL || dayFilter != null
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
    val weekdayPeriod: WeekdayPeriod = WeekdayPeriod.ALL,
    val isWeekdayLoading: Boolean = false,
    val categoryFrequency: CategoryFrequencyResponse? = null,
    val categoryPeriod: WeekdayPeriod = WeekdayPeriod.ALL,
    val isCategoryLoading: Boolean = false,
    // Snapshots semaine pour la carte "Cette semaine" (independants des
    // graphiques principaux qui peuvent etre toggle sur d'autres periodes).
    val weekdayThisWeek: WeekdayFrequencyResponse? = null,
    val categoryThisWeek: CategoryFrequencyResponse? = null,
    val retardTimeline: RetardTimelineResponse? = null,
    val retardPeriod: RetardPeriod = RetardPeriod.ALL,
    val isRetardLoading: Boolean = false,
    val selectedTab: Int = 0,
    val error: UserError? = null,
    // False = gamification off for this user: the gamification data below is
    // never fetched and stays empty/null, and the screen hides its sections.
    val gamificationVisible: Boolean = true,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    @ApplicationContext private val context: Context,
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
                val gamification = tokenManager.gamificationVisibleFlow.first()
                val statsResp = apiService.getStats()
                // Gamification off: skip the calls, the data stays empty/null.
                val weekResp = if (gamification) apiService.getLeaderboard("week") else null
                val monthResp = if (gamification) apiService.getLeaderboard("month") else null
                val allResp = if (gamification) apiService.getLeaderboard("all") else null
                val streaksResp = if (gamification) apiService.getStreaks() else null
                val badgesResp = if (gamification) apiService.getBadges() else null
                val historyResp = apiService.getHistory()
                val fairShareResp = apiService.getFairShareStatus()
                val ptsWeekResp = if (gamification) apiService.getPointsTimeline("week") else null
                val ptsMonthResp = if (gamification) apiService.getPointsTimeline("month") else null
                val ptsAllResp = if (gamification) apiService.getPointsTimeline("all") else null
                val weekdayResp = apiService.getWeekdayFrequency("all")
                val categoryResp = apiService.getCategoryFrequency("all")
                val weekdayThisWeekResp = apiService.getWeekdayFrequency("week")
                val categoryThisWeekResp = apiService.getCategoryFrequency("week")
                val retardResp = apiService.getRetardTimeline("all")

                _uiState.value = StatsUiState(
                    isLoading = false,
                    isRefreshing = false,
                    stats = if (statsResp.isSuccessful) statsResp.body() ?: emptyList() else emptyList(),
                    gamificationVisible = gamification,
                    leaderboardWeek = weekResp?.takeIf { it.isSuccessful }?.body() ?: emptyList(),
                    leaderboardMonth = monthResp?.takeIf { it.isSuccessful }?.body() ?: emptyList(),
                    leaderboardAll = allResp?.takeIf { it.isSuccessful }?.body() ?: emptyList(),
                    streaks = streaksResp?.takeIf { it.isSuccessful }?.body() ?: emptyList(),
                    badges = badgesResp?.takeIf { it.isSuccessful }?.body() ?: emptyList(),
                    history = withoutPoints(
                        historyResp.body().takeIf { historyResp.isSuccessful }.orEmpty(), gamification,
                    ),
                    fairShare = if (fairShareResp.isSuccessful) fairShareResp.body() ?: emptyList() else emptyList(),
                    pointsWeek = ptsWeekResp?.takeIf { it.isSuccessful }?.body(),
                    pointsMonth = ptsMonthResp?.takeIf { it.isSuccessful }?.body(),
                    pointsAll = ptsAllResp?.takeIf { it.isSuccessful }?.body(),
                    weekdayFrequency = if (weekdayResp.isSuccessful) weekdayResp.body() else null,
                    categoryFrequency = if (categoryResp.isSuccessful) categoryResp.body() else null,
                    weekdayThisWeek = if (weekdayThisWeekResp.isSuccessful) weekdayThisWeekResp.body() else null,
                    categoryThisWeek = if (categoryThisWeekResp.isSuccessful) categoryThisWeekResp.body() else null,
                    retardTimeline = if (retardResp.isSuccessful) retardResp.body() else null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = UserErrors.from(context, e))
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    // --- Periode des charts Weekday / Category ---

    fun setWeekdayPeriod(period: WeekdayPeriod) {
        if (_uiState.value.weekdayPeriod == period) return
        _uiState.value = _uiState.value.copy(weekdayPeriod = period, isWeekdayLoading = true)
        viewModelScope.launch {
            try {
                val resp = apiService.getWeekdayFrequency(period.apiValue)
                _uiState.value = _uiState.value.copy(
                    weekdayFrequency = if (resp.isSuccessful) resp.body() else _uiState.value.weekdayFrequency,
                    isWeekdayLoading = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isWeekdayLoading = false)
            }
        }
    }

    fun setRetardPeriod(period: RetardPeriod) {
        if (_uiState.value.retardPeriod == period) return
        _uiState.value = _uiState.value.copy(retardPeriod = period, isRetardLoading = true)
        viewModelScope.launch {
            try {
                val resp = apiService.getRetardTimeline(period.apiValue)
                _uiState.value = _uiState.value.copy(
                    retardTimeline = if (resp.isSuccessful) resp.body() else _uiState.value.retardTimeline,
                    isRetardLoading = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isRetardLoading = false)
            }
        }
    }

    fun setCategoryPeriod(period: WeekdayPeriod) {
        if (_uiState.value.categoryPeriod == period) return
        _uiState.value = _uiState.value.copy(categoryPeriod = period, isCategoryLoading = true)
        viewModelScope.launch {
            try {
                val resp = apiService.getCategoryFrequency(period.apiValue)
                _uiState.value = _uiState.value.copy(
                    categoryFrequency = if (resp.isSuccessful) resp.body() else _uiState.value.categoryFrequency,
                    isCategoryLoading = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isCategoryLoading = false)
            }
        }
    }

    /** Deeplink depuis un tap-on-point d'un chart : applique un filtre sur
     *  une journee precise et bascule sur l'onglet Historique. */
    fun jumpToHistoryForDate(isoDate: String) {
        val newFilters = HistoryFilters(dayFilter = isoDate)
        _uiState.value = _uiState.value.copy(
            historyFilters = newFilters,
            selectedTab = 2,  // Historique (Resume=0, Badges=1, Historique=2)
        )
        refetchHistory(newFilters)
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
     * une nouvelle requete remplace l'ancienne (la derniere arrivee gagne).
     * `dayFilter` (deeplink chart) prend le pas sur `period` quand il est non-null. */
    private fun refetchHistory(filters: HistoryFilters) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isHistoryLoading = true)
            val sinceIso: String?
            val beforeIso: String?
            if (filters.dayFilter != null) {
                // Bornes de la journee en UTC : [00:00 ce jour, 00:00 lendemain[
                sinceIso = "${filters.dayFilter}T00:00:00Z"
                val nextDay = java.time.LocalDate.parse(filters.dayFilter).plusDays(1)
                beforeIso = "${nextDay}T00:00:00Z"
            } else {
                sinceIso = when (filters.period) {
                    HistoryPeriod.ALL -> null
                    HistoryPeriod.WEEK -> isoMinusDays(7)
                    HistoryPeriod.MONTH -> isoMinusDays(30)
                    HistoryPeriod.THREE_MONTHS -> isoMinusDays(90)
                }
                beforeIso = null
            }
            try {
                val resp = apiService.getHistory(
                    limit = 100,
                    before = beforeIso,
                    since = sinceIso,
                    families = filters.families.toList(),
                    actorId = filters.actorId,
                    q = filters.query.ifBlank { null },
                )
                val body = if (resp.isSuccessful) resp.body() ?: emptyList() else emptyList()
                val history = withoutPoints(body, _uiState.value.gamificationVisible)
                _uiState.value = _uiState.value.copy(history = history, isHistoryLoading = false)
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

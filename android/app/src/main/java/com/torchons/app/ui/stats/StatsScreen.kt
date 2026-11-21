package com.torchons.app.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.filled.Favorite
import com.torchons.app.R
import com.torchons.app.ui.common.ReloadOnReconnect
import com.torchons.app.ui.common.UserErrorBlock
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.LeaderboardEntry
import com.torchons.app.data.model.CategoryFrequencyResponse
import com.torchons.app.data.model.PointsTimelineResponse
import com.torchons.app.data.model.RetardTimelineResponse
import com.torchons.app.data.model.StreakInfo
import com.torchons.app.data.model.WeekdayFrequencyResponse
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
import com.torchons.app.util.TaskUrgencyCalculator

// Identites stables des onglets. On dispatche la-dessus et non sur la
// position, qui change des qu'un module est coupe.
private const val TAB_SUMMARY = "summary"
private const val TAB_BADGES = "badges"
private const val TAB_HISTORY = "history"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onTaskClick: (String) -> Unit = {},
    onNavigateToFoyerSettings: () -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // P3 — les onglets ne sont pas tous du meme module. Badges appartient a
    // `motivation`, Historique a `statistics` ; Resume est mixte et reste
    // present des lors que l'ecran est atteignable. On construit donc la liste
    // des onglets VISIBLES et on dispatche sur leur identite, pas sur leur
    // index : sans ca, cacher un onglet decalerait le contenu de tous les
    // suivants.
    val motivationOn = moduleEnabled(Modules.MOTIVATION)
    val statisticsOn = moduleEnabled(Modules.STATISTICS)

    val visibleTabs = buildList {
        add(TAB_SUMMARY)
        if (motivationOn && uiState.gamificationVisible) add(TAB_BADGES)
        if (statisticsOn) add(TAB_HISTORY)
    }
    val tabs = visibleTabs.map {
        when (it) {
            TAB_BADGES -> stringResource(R.string.stats_tab_badges)
            TAB_HISTORY -> stringResource(R.string.stats_tab_history)
            else -> stringResource(R.string.stats_tab_summary)
        }
    }
    // L'index selectionne peut pointer hors de la liste quand un module vient
    // d'etre coupe a distance par l'autre membre.
    val selectedIndex = uiState.selectedTab.coerceIn(0, visibleTabs.lastIndex)

    // Etat pour l'ouverture du modal profil utilisateur.
    var userProfileRequest by remember { mutableStateOf<UserProfileRequest?>(null) }
    // P3 — Etat pour l'ouverture du modal calendrier des series (lecture
    // seule). Stocke l'userId pre-positionne dans le selecteur d'user.
    var streakCalendarUserId by remember { mutableStateOf<String?>(null) }

    // Refresh à chaque ouverture de l'onglet Stats : couvre toute mutation
    // faite ailleurs (tâche complétée → série/record, présence éditée…)
    // sans pull-to-refresh manuel. Cache-conventions règle 1, surface Stats.
    LaunchedEffect(Unit) { viewModel.loadData(isRefresh = true) }
    // A cold start offline left the screen empty, and it stayed so after the network came back
    // (2026-11-20): reload once on reconnect while the load is in error.
    ReloadOnReconnect(hasError = uiState.error != null || uiState.historyFailed) {
        viewModel.loadData(isRefresh = true)
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.loadData(isRefresh = true) },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatsTabBar(tabs, selectedIndex, onSelect = viewModel::selectTab)
            uiState.error?.let { UserErrorBlock(it, "Stats", modifier = Modifier.padding(horizontal = 16.dp)) }

            val onUserClick: (UserProfileRequest) -> Unit = { req -> userProfileRequest = req }
            val currentUserId by viewModel.userId.collectAsState()
            // Affordance dediee (decision 10) : ouvre le modal calendrier des
            // series avec le selecteur d'user pre-positionne. Distinct du
            // tap-sur-nom generique (qui garde le modal profil).
            val onOpenStreakCalendar: (String) -> Unit = { uid ->
                streakCalendarUserId = uid
            }

            when (visibleTabs[selectedIndex]) {
                TAB_SUMMARY -> ResumeTab(
                    gamificationVisible = uiState.gamificationVisible,
                    streaks = uiState.streaks,
                    leaderboardWeek = uiState.leaderboardWeek,
                    leaderboardMonth = uiState.leaderboardMonth,
                    leaderboardAll = uiState.leaderboardAll,
                    retardTimeline = uiState.retardTimeline,
                    retardPeriod = uiState.retardPeriod,
                    isRetardLoading = uiState.isRetardLoading,
                    onRetardPeriodChange = viewModel::setRetardPeriod,
                    pointsWeek = uiState.pointsWeek,
                    pointsMonth = uiState.pointsMonth,
                    pointsAll = uiState.pointsAll,
                    weekdayFrequency = uiState.weekdayFrequency,
                    weekdayPeriod = uiState.weekdayPeriod,
                    isWeekdayLoading = uiState.isWeekdayLoading,
                    onWeekdayPeriodChange = viewModel::setWeekdayPeriod,
                    categoryFrequency = uiState.categoryFrequency,
                    categoryPeriod = uiState.categoryPeriod,
                    isCategoryLoading = uiState.isCategoryLoading,
                    onCategoryPeriodChange = viewModel::setCategoryPeriod,
                    weekdayThisWeek = uiState.weekdayThisWeek,
                    categoryThisWeek = uiState.categoryThisWeek,
                    onPointTap = viewModel::jumpToHistoryForDate,
                    fairShare = uiState.fairShare,
                    currentUserId = currentUserId,
                    onUserClick = onUserClick,
                    onOpenStreakCalendar = onOpenStreakCalendar,
                )
                TAB_BADGES -> BadgesTab(uiState.badges)
                TAB_HISTORY -> HistoryTab(
                    history = uiState.history,
                    filters = uiState.historyFilters,
                    isHistoryLoading = uiState.isHistoryLoading,
                    loadFailed = uiState.historyFailed,
                    onRetry = viewModel::retryHistory,
                    users = uiState.stats.map { it.userId to it.displayName },
                    onQueryChange = viewModel::updateHistoryQuery,
                    onToggleFamily = viewModel::toggleHistoryFamily,
                    onSetActor = viewModel::setHistoryActor,
                    onSetPeriod = viewModel::setHistoryPeriod,
                    onClearFilters = viewModel::clearHistoryFilters,
                    onTaskClick = onTaskClick,
                    onUserClick = onUserClick
                )
            }
        }
    }

    // Modal profil utilisateur (affiche quand un nom est clique). Le
    // tap-sur-nom generique reste inchange (decision 10) : il ouvre TOUJOURS
    // ce modal profil. On lui ajoute juste un lien vers le calendrier series.
    userProfileRequest?.let { req ->
        UserProfileDialog(
            request = req,
            onDismiss = { userProfileRequest = null },
            onTaskClick = { taskId ->
                userProfileRequest = null
                onTaskClick(taskId)
            },
            onOpenStreakCalendar = {
                val uid = req.userId
                userProfileRequest = null
                streakCalendarUserId = uid
            },
        )
    }

    // P4b — Modal UNIQUE "Présence & séries" (éditable). Membres = liste
    // des users du foyer (stats), fallback sur les streaks si stats vide.
    streakCalendarUserId?.let { uid ->
        val members = remember(uiState.stats, uiState.streaks) {
            val fromStats = uiState.stats.map {
                StreakMember(it.userId, it.displayName)
            }
            val list = fromStats.ifEmpty {
                uiState.streaks.map { StreakMember(it.userId, it.displayName) }
            }
            list.distinctBy { it.userId }
        }
        StreakCalendarModal(
            members = members,
            initialUserId = uid,
            onDismiss = {
                streakCalendarUserId = null
                // P4 #9 — refresh des stats au close du modal pour que le
                // bloc série du Résumé reflète une édition de présence
                // SANS pull-to-refresh manuel (cache-conventions règle 1).
                viewModel.loadData(isRefresh = true)
            },
        )
    }
}

@Composable
private fun ResumeTab(
    gamificationVisible: Boolean,
    streaks: List<StreakInfo>,
    leaderboardWeek: List<LeaderboardEntry>,
    leaderboardMonth: List<LeaderboardEntry>,
    leaderboardAll: List<LeaderboardEntry>,
    retardTimeline: RetardTimelineResponse?,
    retardPeriod: RetardPeriod,
    isRetardLoading: Boolean,
    onRetardPeriodChange: (RetardPeriod) -> Unit,
    pointsWeek: PointsTimelineResponse?,
    pointsMonth: PointsTimelineResponse?,
    pointsAll: PointsTimelineResponse?,
    weekdayFrequency: WeekdayFrequencyResponse?,
    weekdayPeriod: WeekdayPeriod,
    isWeekdayLoading: Boolean,
    onWeekdayPeriodChange: (WeekdayPeriod) -> Unit,
    categoryFrequency: CategoryFrequencyResponse?,
    categoryPeriod: WeekdayPeriod,
    isCategoryLoading: Boolean,
    onCategoryPeriodChange: (WeekdayPeriod) -> Unit,
    weekdayThisWeek: WeekdayFrequencyResponse?,
    categoryThisWeek: CategoryFrequencyResponse?,
    onPointTap: (String) -> Unit,
    fairShare: List<FairShareStatus>,
    currentUserId: String?,
    onUserClick: (UserProfileRequest) -> Unit,
    onOpenStreakCalendar: (String) -> Unit,
) {
    val objectivesOn = moduleEnabled(Modules.OBJECTIVES)
    val repartitionOn = moduleEnabled(Modules.REPARTITION)

    val myFairShare = fairShare.find { it.userId == currentUserId }
    val otherFairShare = fairShare.find { it.userId != currentUserId }
    // Le message "tu as fait plus que ta part" est du fair-share, donc du
    // module Repartition — pas de la gamification.
    val showOverPerformingCard = repartitionOn &&
        myFairShare != null && myFairShare.aboveTarget && otherFairShare != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // v0.31 — Resume hebdomadaire en haut de Stats. Self-contained
        // (charge sa data en interne via /api/stats/weekly-summary).
        item { WeeklySummaryCard(gamificationVisible = gamificationVisible) }

        // v0.31 — Stats long-terme (repartition par piece + temps cumule).
        item { LongTermCard(gamificationVisible = gamificationVisible) }

        // v0.31 — Objectifs personnels et partages.
        // P3 : appartient au module `objectives`.
        if (objectivesOn) {
            item { ObjectivesCard() }
        }

        // Message fair-share sur-performance (deplace depuis HomeScreen v0.8.0)
        if (showOverPerformingCard) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = TaskUrgencyCalculator.VERT.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = TaskUrgencyCalculator.VERT,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.stats_fair_share_over_format, otherFairShare!!.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TaskUrgencyCalculator.VERT
                        )
                    }
                }
            }
        }

        // 1) Streaks en premier — bloc identique a l'ancien (titre + carte par user).
        if (gamificationVisible && streaks.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.stats_streaks_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(streaks) { streak ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (streak.currentStreak > 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = if (streak.currentStreak > 0)
                                TaskUrgencyCalculator.ORANGE else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = streak.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable {
                                    onUserClick(
                                        UserProfileRequest(streak.userId, streak.displayName)
                                    )
                                }
                            )
                            Text(
                                text = stringResource(R.string.stats_streak_record_format, streak.bestStreak),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.stats_streak_current_days_format, streak.currentStreak),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (streak.currentStreak > 0)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Affordance dediee (decision 10) : ouvre le modal
                        // calendrier des series pre-positionne sur CE membre.
                        // N'altere PAS le tap-sur-nom (qui ouvre le profil).
                        IconButton(
                            onClick = { onOpenStreakCalendar(streak.userId) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = stringResource(R.string.stats_streak_calendar_open_format, streak.displayName),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // 2) Carte "Cette semaine" — recap leger avant les graphiques.
        item {
            Spacer(modifier = Modifier.height(4.dp))
            WeekRecapCard(
                pointsWeek = pointsWeek,
                weekdayThisWeek = weekdayThisWeek,
                categoryThisWeek = categoryThisWeek,
                currentUserId = currentUserId,
            )
        }

        // 3) Carte graphiques — pager 6 vues.
        item {
            Spacer(modifier = Modifier.height(4.dp))
            ChartsCard(
                gamificationVisible = gamificationVisible,
                retardTimeline = retardTimeline,
                retardPeriod = retardPeriod,
                isRetardLoading = isRetardLoading,
                onRetardPeriodChange = onRetardPeriodChange,
                pointsWeek = pointsWeek,
                pointsMonth = pointsMonth,
                pointsAll = pointsAll,
                weekdayFrequency = weekdayFrequency,
                weekdayPeriod = weekdayPeriod,
                isWeekdayLoading = isWeekdayLoading,
                onWeekdayPeriodChange = onWeekdayPeriodChange,
                categoryFrequency = categoryFrequency,
                categoryPeriod = categoryPeriod,
                isCategoryLoading = isCategoryLoading,
                onCategoryPeriodChange = onCategoryPeriodChange,
                onPointTap = onPointTap,
            )
        }

        // 3) Classement combine — Semaine / Mois / Total avec ligne "Total" en plus.
        if (gamificationVisible) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stats_leaderboard_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Text(
                    text = stringResource(R.string.stats_leaderboard_this_week),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(leaderboardWeek) { entry -> LeaderboardRow(entry, onUserClick) }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stats_leaderboard_this_month),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(leaderboardMonth) { entry -> LeaderboardRow(entry, onUserClick) }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stats_leaderboard_all_time),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(leaderboardAll) { entry -> LeaderboardRow(entry, onUserClick) }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

package com.torchons.app.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.filled.Favorite
import com.torchons.app.R
import com.torchons.app.data.model.BadgeInfo
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onTaskClick: (String) -> Unit = {},
    onNavigateToFoyerSettings: () -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tabs = listOf(
        stringResource(R.string.stats_tab_summary),
        stringResource(R.string.stats_tab_badges),
        stringResource(R.string.stats_tab_history),
    )

    // Etat pour l'ouverture du modal profil utilisateur.
    var userProfileRequest by remember { mutableStateOf<UserProfileRequest?>(null) }
    // P3 — Etat pour l'ouverture du modal calendrier des series (lecture
    // seule). Stocke l'userId pre-positionne dans le selecteur d'user.
    var streakCalendarUserId by remember { mutableStateOf<String?>(null) }

    // Refresh à chaque ouverture de l'onglet Stats : couvre toute mutation
    // faite ailleurs (tâche complétée → série/record, présence éditée…)
    // sans pull-to-refresh manuel. Cache-conventions règle 1, surface Stats.
    LaunchedEffect(Unit) { viewModel.loadData(isRefresh = true) }

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
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Text(
                                text = title,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            val onUserClick: (UserProfileRequest) -> Unit = { req -> userProfileRequest = req }
            val currentUserId by viewModel.userId.collectAsState()
            // Affordance dediee (decision 10) : ouvre le modal calendrier des
            // series avec le selecteur d'user pre-positionne. Distinct du
            // tap-sur-nom generique (qui garde le modal profil).
            val onOpenStreakCalendar: (String) -> Unit = { uid ->
                streakCalendarUserId = uid
            }

            when (uiState.selectedTab) {
                0 -> ResumeTab(
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
                1 -> BadgesTab(uiState.badges)
                2 -> HistoryTab(
                    history = uiState.history,
                    filters = uiState.historyFilters,
                    isHistoryLoading = uiState.isHistoryLoading,
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
    val myFairShare = fairShare.find { it.userId == currentUserId }
    val otherFairShare = fairShare.find { it.userId != currentUserId }
    val showOverPerformingCard = myFairShare != null && myFairShare.aboveTarget && otherFairShare != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // v0.31 — Resume hebdomadaire en haut de Stats. Self-contained
        // (charge sa data en interne via /api/stats/weekly-summary).
        item { WeeklySummaryCard() }

        // v0.31 — Stats long-terme (repartition par piece + temps cumule).
        item { LongTermCard() }

        // v0.31 — Objectifs personnels et partages.
        item { ObjectivesCard() }

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
        if (streaks.isNotEmpty()) {
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

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Carte "Statistiques" : un selecteur d'onglets en haut + un HorizontalPager
 * (4 vues) couplé. L'utilisateur peut swiper ou cliquer sur un onglet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartsCard(
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
    onPointTap: (String) -> Unit,
) {
    // Retard foyer en 1ere position : c'est la metrique commune au foyer
    // (vs les courses de points qui sont per-user).
    val titles = listOf(
        stringResource(R.string.stats_charts_tab_overdue),
        stringResource(R.string.stats_charts_tab_week),
        stringResource(R.string.stats_charts_tab_month),
        stringResource(R.string.stats_charts_tab_alltime),
        stringResource(R.string.stats_charts_tab_weekday),
        stringResource(R.string.stats_charts_tab_category),
    )
    val pagerState = rememberPagerState(pageCount = { titles.size })
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.stats_charts_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(R.string.stats_charts_swipe_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            // Selecteur d'onglets cliquable, synchronise avec le pager.
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                titles.forEachIndexed { i, title ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { page ->
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    when (page) {
                        0 -> RetardContent(
                            title = stringResource(R.string.stats_charts_overdue_title),
                            data = retardTimeline,
                            period = retardPeriod,
                            isLoading = isRetardLoading,
                            onPeriodChange = onRetardPeriodChange,
                        )
                        1 -> ChartContent(
                            title = stringResource(R.string.stats_charts_points_week_title),
                            data = pointsWeek,
                            onPointTap = onPointTap,
                        )
                        2 -> ChartContent(
                            title = stringResource(R.string.stats_charts_points_month_title),
                            data = pointsMonth,
                            onPointTap = onPointTap,
                        )
                        3 -> ChartContent(
                            title = stringResource(R.string.stats_charts_points_all_title),
                            data = pointsAll,
                            onPointTap = onPointTap,
                        )
                        4 -> WeekdayContent(
                            title = stringResource(R.string.stats_charts_weekday_title),
                            data = weekdayFrequency,
                            period = weekdayPeriod,
                            isLoading = isWeekdayLoading,
                            onPeriodChange = onWeekdayPeriodChange,
                        )
                        5 -> CategoryContent(
                            title = stringResource(R.string.stats_charts_category_title),
                            data = categoryFrequency,
                            period = categoryPeriod,
                            isLoading = isCategoryLoading,
                            onPeriodChange = onCategoryPeriodChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetardContent(
    title: String,
    data: RetardTimelineResponse?,
    period: RetardPeriod,
    isLoading: Boolean,
    onPeriodChange: (RetardPeriod) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (data == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        RetardTimelineChart(
            data = data,
            period = period,
            isLoading = isLoading,
            onPeriodChange = onPeriodChange,
        )
    }
}

@Composable
private fun ChartContent(
    title: String,
    data: PointsTimelineResponse?,
    onPointTap: (String) -> Unit = {},
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (data == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        PointsTimelineChart(data = data, onPointTap = onPointTap)
    }
}

@Composable
private fun WeekdayContent(
    title: String,
    data: WeekdayFrequencyResponse?,
    period: WeekdayPeriod,
    isLoading: Boolean,
    onPeriodChange: (WeekdayPeriod) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (data == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        WeekdayFrequencyChart(
            data = data,
            period = period,
            isLoading = isLoading,
            onPeriodChange = onPeriodChange,
        )
    }
}

@Composable
private fun CategoryContent(
    title: String,
    data: CategoryFrequencyResponse?,
    period: WeekdayPeriod,
    isLoading: Boolean,
    onPeriodChange: (WeekdayPeriod) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (data == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        CategoryFrequencyChart(
            data = data,
            period = period,
            isLoading = isLoading,
            onPeriodChange = onPeriodChange,
        )
    }
}

@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.rank == 1) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rang
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.rank == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (entry.rank == 1) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.stats_leaderboard_rank_format, entry.rank),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        onUserClick(UserProfileRequest(entry.userId, entry.displayName))
                    }
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.stats_leaderboard_score_format, entry.normalizedScore.toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.stats_leaderboard_breakdown_format, entry.points, entry.tasksCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Recap "Cette semaine" pour le user courant : ses points de la semaine + delta
 * vs sem precedente, top jour de semaine et top categorie. Carte legere qui
 * vit en haut du Resume et reuse les donnees deja chargees (pas de query
 * supplementaire au-dela des 2 fetches "this week" du ViewModel).
 */
@Composable
private fun WeekRecapCard(
    pointsWeek: PointsTimelineResponse?,
    weekdayThisWeek: WeekdayFrequencyResponse?,
    categoryThisWeek: CategoryFrequencyResponse?,
    currentUserId: String?,
) {
    // Trouver le user courant dans pointsWeek pour son delta personnel.
    val me = pointsWeek?.users?.firstOrNull { it.userId == currentUserId }
    val myCurrent = me?.points?.lastOrNull()?.cumulative ?: 0
    val myPrevious = me?.pointsPrevious

    // Top weekday : on aggrege tous les users (foyer-level pour la lecture
    // rapide), prend le max. Indice 0=lundi..6=dimanche.
    val weekdaySum = IntArray(7)
    weekdayThisWeek?.users?.forEach { u ->
        u.counts.take(7).forEachIndexed { i, c -> weekdaySum[i] += c }
    }
    val topWeekdayIdx = if (weekdaySum.any { it > 0 })
        weekdaySum.indices.maxBy { weekdaySum[it] } else null
    val weekdayLabels = listOf(
        stringResource(R.string.weekday_monday),
        stringResource(R.string.weekday_tuesday),
        stringResource(R.string.weekday_wednesday),
        stringResource(R.string.weekday_thursday),
        stringResource(R.string.weekday_friday),
        stringResource(R.string.weekday_saturday),
        stringResource(R.string.weekday_sunday),
    )

    // Top categorie cette semaine : la 1ere de la liste (deja triee desc backend).
    val topCategory = categoryThisWeek?.categories?.firstOrNull { it.total > 0 }

    // Si rien a montrer, on n'affiche pas la carte (foyer fraichement cree
    // ou debut de semaine encore vide).
    val nothingToShow = me == null && topWeekdayIdx == null && topCategory == null
    if (nothingToShow) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_weekrecap_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            // Ligne 1 : mes points + delta. Mise en valeur visuelle.
            if (me != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "$myCurrent",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.stats_weekrecap_points_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (myPrevious != null) {
                        Spacer(Modifier.width(12.dp))
                        val deltaText = formatWeekDelta(myCurrent, myPrevious)
                        val deltaColor = when {
                            myPrevious == 0 && myCurrent == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            myPrevious == 0 -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            myCurrent > myPrevious -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            myCurrent < myPrevious -> androidx.compose.ui.graphics.Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = deltaColor,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Ligne 2 : top jour + top categorie cote foyer (lecture rapide).
            if (topWeekdayIdx != null) {
                val nbTop = weekdaySum[topWeekdayIdx]
                val countLabel = pluralStringResource(R.plurals.stats_top_day_count, nbTop, nbTop)
                Text(
                    text = "${stringResource(R.string.stats_weekrecap_top_day_format, weekdayLabels[topWeekdayIdx])} $countLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (topCategory != null) {
                val countLabel = pluralStringResource(R.plurals.stats_top_category_count, topCategory.total, topCategory.total)
                Text(
                    text = "${stringResource(R.string.stats_weekrecap_top_category_format, topCategory.categoryName)} $countLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Formate "+15% vs sem prec." / "-20%" / "= vs sem prec." pour le recap. */
@Composable
private fun formatWeekDelta(current: Int, previous: Int): String {
    if (previous == 0 && current == 0) return stringResource(R.string.stats_weekrecap_delta_same)
    if (previous == 0) return stringResource(R.string.stats_weekrecap_delta_zero_to_some)
    val pct = ((current - previous).toDouble() / previous.toDouble() * 100.0).toInt()
    return when {
        pct == 0 -> stringResource(R.string.stats_weekrecap_delta_same)
        pct > 0 -> stringResource(R.string.stats_weekrecap_delta_positive_format, pct)
        else -> stringResource(R.string.stats_weekrecap_delta_negative_format, pct)
    }
}

@Composable
private fun BadgesTab(badges: List<BadgeInfo>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val earned = badges.filter { it.earned }
        val locked = badges.filter { !it.earned }

        if (earned.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.stats_badges_earned_format, earned.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(earned) { badge -> BadgeRow(badge) }
        }

        if (locked.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.stats_badges_locked_format, locked.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(locked) { badge -> BadgeRow(badge) }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun BadgeRow(badge: BadgeInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (badge.earned) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (badge.earned) Icons.Default.Star else Icons.Default.Lock,
                contentDescription = null,
                tint = if (badge.earned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = badge.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (badge.earned) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTab(
    history: List<Map<String, Any>>,
    filters: HistoryFilters,
    isHistoryLoading: Boolean,
    users: List<Pair<String, String>>,        // (userId, displayName)
    onQueryChange: (String) -> Unit,
    onToggleFamily: (String) -> Unit,
    onSetActor: (String?) -> Unit,
    onSetPeriod: (HistoryPeriod) -> Unit,
    onClearFilters: () -> Unit,
    onTaskClick: (String) -> Unit,
    onUserClick: (UserProfileRequest) -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    val activeFilterCount = listOf(
        filters.families.isNotEmpty(),
        filters.actorId != null,
        filters.period != HistoryPeriod.ALL,
        filters.dayFilter != null,
    ).count { it }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Chip dedie "Activites du JJ/MM" — visible uniquement quand on
        // arrive ici via tap-on-point d'un graphique. Cliquer sur la croix
        // efface ce filtre (et tous les autres, geste "retour a la normale").
        if (filters.dayFilter != null) {
            val isoDate = filters.dayFilter
            val pretty = remember(isoDate) {
                try {
                    val d = java.time.LocalDate.parse(isoDate)
                    d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                } catch (_: Exception) { isoDate }
            }
            FilterChip(
                selected = true,
                onClick = { onClearFilters() },
                label = { Text(stringResource(R.string.stats_history_day_filter_format, pretty)) },
                trailingIcon = {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.stats_history_clear_day_filter), modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // Barre de recherche persistante en haut.
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.stats_history_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.stats_history_clear_search))
                    }
                }
            },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Bouton "Filtres" (avec badge si filtres actifs) + lien "Effacer".
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge { Text(activeFilterCount.toString()) }
                    }
                }
            ) {
                OutlinedButton(onClick = { showFilters = !showFilters }) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (showFilters) stringResource(R.string.stats_history_filters_hide)
                        else stringResource(R.string.stats_history_filters_show)
                    )
                }
            }
            if (filters.hasActiveFilters || filters.query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    onClearFilters()
                    if (filters.query.isNotEmpty()) onQueryChange("")
                }) { Text(stringResource(R.string.stats_history_clear_filters)) }
            }
        }

        // Panneau pliable des filtres.
        AnimatedVisibility(visible = showFilters) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                // --- Type (familles) ---
                Text(
                    stringResource(R.string.stats_history_filter_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "completions" to stringResource(R.string.stats_history_filter_type_completions),
                        "votes" to stringResource(R.string.stats_history_filter_type_votes),
                        "modifications" to stringResource(R.string.stats_history_filter_type_modifications),
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = filters.families.contains(key),
                            onClick = { onToggleFamily(key) },
                            label = { Text(label) },
                        )
                    }
                }

                // --- Acteur ---
                Text(
                    stringResource(R.string.stats_history_filter_actor),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = filters.actorId == null,
                        onClick = { onSetActor(null) },
                        label = { Text(stringResource(R.string.stats_history_filter_actor_all)) },
                    )
                    users.forEach { (uid, name) ->
                        FilterChip(
                            selected = filters.actorId == uid,
                            onClick = { onSetActor(uid) },
                            label = { Text(name) },
                        )
                    }
                }

                // --- Periode ---
                Text(
                    stringResource(R.string.stats_history_filter_period),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        HistoryPeriod.WEEK to stringResource(R.string.stats_history_filter_period_week),
                        HistoryPeriod.MONTH to stringResource(R.string.stats_history_filter_period_month),
                        HistoryPeriod.THREE_MONTHS to stringResource(R.string.stats_history_filter_period_three_months),
                        HistoryPeriod.ALL to stringResource(R.string.stats_history_filter_period_all),
                    ).forEach { (period, label) ->
                        FilterChip(
                            selected = filters.period == period,
                            onClick = { onSetPeriod(period) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // Indicateur de chargement subtil pendant les refetch (filtres).
        if (isHistoryLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (filters.hasActiveFilters || filters.query.isNotEmpty())
                                stringResource(R.string.stats_history_empty_filtered)
                            else stringResource(R.string.stats_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(history.size) { index ->
            val event = history[index]
            val type = event["type"] as? String ?: ""
            val desc = event["description"] as? String ?: ""
            val date = event["date"] as? String ?: ""
            val actor = event["actor"] as? Map<*, *>
            val actorId = actor?.get("id") as? String
            val actorName = actor?.get("name") as? String
            val task = event["task"] as? Map<*, *>
            val taskId = task?.get("id") as? String
            val taskTitle = task?.get("title") as? String
            val meta = event["meta"] as? Map<*, *>
            // Pour un collectif, meta porte la liste complete (ids + noms)
            // pour rendre chaque participant cliquable. Sinon on retombe
            // sur le seul actor (cas solo et autres types d'evenements).
            val participantIds = (meta?.get("participant_ids") as? List<*>)?.mapNotNull { it as? String }
            val participantNames = (meta?.get("participant_names") as? List<*>)?.mapNotNull { it as? String }
            val clickables: List<Pair<String, String>> = when {
                participantIds != null && participantNames != null
                    && participantIds.size == participantNames.size ->
                    participantIds.zip(participantNames)
                actorId != null && actorName != null -> listOf(actorId to actorName)
                else -> emptyList()
            }

            val icon = when (type) {
                "completion" -> "✅"
                "affinity" -> "❤️"
                "effort" -> "⏱️"
                "task_created" -> "➕"
                "task_updated" -> "✏️"
                "task_deleted" -> "🗑️"
                "category_changed" -> "🏷️"
                "completion_edited" -> "✏️"
                "completion_deleted" -> "🗑️"
                "modification" -> "✏️" // retro-compat ancien backend
                else -> "•"
            }
            val formattedDate = try {
                val instant = java.time.Instant.parse(date)
                val local = instant.atZone(java.time.ZoneId.systemDefault())
                local.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            } catch (_: Exception) { date.take(16) }

            // La Card est cliquable vers la tache si on a un taskId.
            val cardModifier = if (taskId != null) {
                Modifier
                    .fillMaxWidth()
                    .clickable { onTaskClick(taskId) }
            } else {
                Modifier.fillMaxWidth()
            }

            // Construction d'un AnnotatedString unique pour la description :
            // chaque participant est rendu cliquable (souligne + couleur primaire)
            // dans un seul Text → baseline coherente, plus de decalage vertical.
            val primaryColor = MaterialTheme.colorScheme.primary
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface
            val descAnnotated = remember(desc, clickables, primaryColor, onSurfaceColor) {
                buildAnnotatedString {
                    append(desc)
                    addStyle(SpanStyle(color = onSurfaceColor), 0, desc.length)
                    // Reperer chaque nom dans l'ordre, sans chevauchement.
                    var cursor = 0
                    for ((uid, name) in clickables) {
                        val idx = desc.indexOf(name, startIndex = cursor)
                        if (idx >= 0) {
                            addStyle(
                                SpanStyle(
                                    color = primaryColor,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                idx, idx + name.length,
                            )
                            addStringAnnotation(
                                tag = "user",
                                annotation = uid,
                                start = idx, end = idx + name.length,
                            )
                            cursor = idx + name.length
                        }
                    }
                }
            }

            Card(
                modifier = cardModifier,
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Text(icon, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        ClickableText(
                            text = descAnnotated,
                            style = MaterialTheme.typography.bodyMedium.copy(color = onSurfaceColor),
                            onClick = { offset ->
                                val ann = descAnnotated.getStringAnnotations(
                                    tag = "user", start = offset, end = offset
                                ).firstOrNull()
                                if (ann != null) {
                                    val name = descAnnotated.text
                                        .substring(ann.start, ann.end)
                                    onUserClick(UserProfileRequest(ann.item, name))
                                } else if (taskId != null) {
                                    // Fallback : si on tape ailleurs sur la ligne,
                                    // on ouvre la tache (comme la card cliquable).
                                    onTaskClick(taskId)
                                }
                            },
                        )
                        Text(
                            formattedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

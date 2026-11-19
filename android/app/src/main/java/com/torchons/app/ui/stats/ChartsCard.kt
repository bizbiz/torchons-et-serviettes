package com.torchons.app.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.CategoryFrequencyResponse
import com.torchons.app.data.model.PointsTimelineResponse
import com.torchons.app.data.model.RetardTimelineResponse
import com.torchons.app.data.model.WeekdayFrequencyResponse
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import kotlinx.coroutines.launch

/**
 * Carte "Statistiques" : un selecteur d'onglets en haut + un HorizontalPager
 * (4 vues) couplé. L'utilisateur peut swiper ou cliquer sur un onglet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartsCard(
    gamificationVisible: Boolean,
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
    val allTitles = listOf(
        stringResource(R.string.stats_charts_tab_overdue),
        stringResource(R.string.stats_charts_tab_week),
        stringResource(R.string.stats_charts_tab_month),
        stringResource(R.string.stats_charts_tab_alltime),
        stringResource(R.string.stats_charts_tab_weekday),
        stringResource(R.string.stats_charts_tab_category),
    )
    // Without gamification the three points-race pages (1..3) are dropped.
    // The backlog page (0) belongs to `objectives`, like the home curve: dropped with it.
    val retardOn = moduleEnabled(Modules.OBJECTIVES)
    val pageIds = (if (gamificationVisible) allTitles.indices.toList() else listOf(0, 4, 5))
        .filter { it != 0 || retardOn }
    val titles = pageIds.map { allTitles[it] }
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
                    when (pageIds[page]) {
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


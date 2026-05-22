package com.torchons.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.WeeklySummaryResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel du resume hebdomadaire (v0.31). Charge `/api/stats/weekly-
 * summary` selon le `weekOffset` courant + permet de naviguer entre
 * semaines via prev / next.
 */
@HiltViewModel
class WeeklySummaryViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    data class State(
        val weekOffset: Int = 0,
        val data: WeeklySummaryResponse? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    fun load(weekOffset: Int) {
        _uiState.update { it.copy(weekOffset = weekOffset, isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.getWeeklySummary(weekOffset)
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(isLoading = false, data = resp.body())
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Erreur ${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Erreur reseau")
                }
            }
        }
    }
}

/**
 * Card "Cette semaine" affichee en haut de StatsScreen (v0.31). Resume
 * du foyer + per-user + top 3 taches sur la fenetre lundi-dimanche.
 *
 * Navigation entre semaines via fleches gauche/droite. Clic sur le label
 * de date revient a la semaine en cours (week_offset = 0).
 */
@Composable
fun WeeklySummaryCard(
    viewModel: WeeklySummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load(0) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header : navigation + titre
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.load(state.weekOffset - 1) },
                    enabled = !state.isLoading,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.weekly_summary_prev_week_cd),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headerLabel(state.weekOffset),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val period = state.data?.let { weekRange(it.weekStart, it.weekEnd) }
                    if (period != null) {
                        Text(
                            text = period,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.load(state.weekOffset + 1) },
                    enabled = !state.isLoading && state.weekOffset < 0,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.weekly_summary_next_week_cd),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading && state.data == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                }

                state.error != null && state.data == null -> {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.data != null -> SummaryBody(state.data!!)
            }
        }
    }
}

@Composable
private fun SummaryBody(data: WeeklySummaryResponse) {
    // Totaux foyer
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatBig(
            value = data.foyerTotalCompletions.toString(),
            label = stringResource(R.string.weekly_summary_stat_tasks),
        )
        StatBig(
            value = data.foyerTotalPoints.toString(),
            label = stringResource(R.string.weekly_summary_stat_points),
        )
    }

    if (data.perUser.isEmpty() || data.foyerTotalCompletions == 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.weekly_summary_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Spacer(Modifier.height(16.dp))

    // Per user — barres simples
    data.perUser.forEachIndexed { index, user ->
        if (index > 0) Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (index == 0 && user.completionsCount > 0) "🏆 ${user.displayName}"
                else user.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.weekly_summary_per_user_format,
                    user.completionsCount,
                    user.pointsEarned,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (data.topTasks.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.weekly_summary_top_tasks),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        data.topTasks.take(3).forEach { task ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = "•  ${task.title}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "x${task.count}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatBig(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun headerLabel(weekOffset: Int): String = when (weekOffset) {
    0 -> stringResource(R.string.weekly_summary_this_week)
    -1 -> stringResource(R.string.weekly_summary_last_week)
    else -> pluralStringResource(
        R.plurals.weekly_summary_weeks_ago_format,
        -weekOffset,
        -weekOffset,
    )
}

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM")

@Composable
private fun weekRange(weekStart: String, weekEnd: String): String? {
    val parsed: Pair<String, String>? = try {
        val start = LocalDate.parse(weekStart).format(DATE_FMT)
        val end = LocalDate.parse(weekEnd).format(DATE_FMT)
        start to end
    } catch (_: Exception) {
        null
    }
    return parsed?.let { (s, e) ->
        stringResource(R.string.weekly_summary_period_format, s, e)
    }
}

package com.torchons.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.LongTermResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel des stats long-terme (v0.31). Charge /api/stats/long-term avec
 * period configurable (month / quarter / year).
 */
@HiltViewModel
class LongTermStatsViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    data class State(
        val period: String = "quarter",
        val data: LongTermResponse? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    fun load(period: String) {
        _uiState.update { it.copy(period = period, isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.getLongTermStats(period)
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, data = resp.body()) }
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
 * Card "Stats long-terme" affichee dans StatsScreen (v0.31).
 *
 * Item roadmap "Stats long-terme et par dimension" : montre la
 * repartition des completions par piece sur une fenetre configurable
 * (mois / trimestre / annee), avec barres horizontales + temps estime
 * cumule deduit du consensus effort.
 */
@Composable
fun LongTermCard(
    viewModel: LongTermStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.load("quarter") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.longterm_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            // Selecteur de periode
            val periods = listOf(
                "month" to stringResource(R.string.longterm_period_month),
                "quarter" to stringResource(R.string.longterm_period_quarter),
                "year" to stringResource(R.string.longterm_period_year),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                periods.forEach { (key, label) ->
                    FilterChip(
                        selected = state.period == key,
                        onClick = { viewModel.load(key) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading && state.data == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                state.error != null && state.data == null -> {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.data != null -> {
                    val data = state.data!!
                    val totalMinutes = data.byRoom.sumOf { it.estimatedMinutes }
                    val totalCompletions = data.byRoom.sumOf { it.completions }

                    if (totalCompletions == 0) {
                        Text(
                            text = stringResource(R.string.longterm_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Header : total temps + total taches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.longterm_total_tasks_format,
                                    totalCompletions,
                                    totalCompletions,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (totalMinutes > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.longterm_cumulated_format,
                                        formatMinutes(totalMinutes),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // Top 6 pieces avec bar
                        val maxCompletions = data.byRoom.maxOfOrNull { it.completions } ?: 1
                        data.byRoom.take(6).forEach { room ->
                            RoomBar(
                                roomName = room.roomName,
                                completions = room.completions,
                                points = room.points,
                                minutes = room.estimatedMinutes,
                                fraction = room.completions.toFloat() / maxCompletions,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomBar(
    roomName: String,
    completions: Int,
    points: Int,
    minutes: Int,
    fraction: Float,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = roomName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (minutes > 0) stringResource(
                    R.string.longterm_room_minutes_format,
                    completions,
                    formatMinutes(minutes),
                ) else stringResource(
                    R.string.longterm_room_points_format,
                    completions,
                    points,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Formate des minutes en "Xh Ymin" ou "Ymin" si <60. */
private fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return "0min"
    if (minutes < 60) return "${minutes}min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h${"%02d".format(m)}"
}

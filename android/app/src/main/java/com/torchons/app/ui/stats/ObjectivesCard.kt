package com.torchons.app.ui.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Objective
import com.torchons.app.data.model.ObjectiveCreateRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * ViewModel des objectifs (v0.31). Charge la liste + permet de creer/supprimer.
 *
 * Pas d'edit pour la v1 — le user supprime + recree si besoin de modifier.
 */
@HiltViewModel
class ObjectivesViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    data class State(
        val objectives: List<Objective> = emptyList(),
        val isLoading: Boolean = false,
        val isCreating: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(State())
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.listObjectives()
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            objectives = resp.body() ?: emptyList(),
                        )
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

    fun create(title: String, period: String, targetCount: Int) {
        if (_uiState.value.isCreating || title.isBlank()) return
        _uiState.update { it.copy(isCreating = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = apiService.createObjective(
                    ObjectiveCreateRequest(
                        title = title.trim(),
                        period = period,
                        targetCount = targetCount,
                        kind = "shared",
                    )
                )
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(isCreating = false) }
                    load()
                } else {
                    _uiState.update {
                        it.copy(isCreating = false, error = "Erreur ${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCreating = false, error = e.message ?: "Erreur reseau")
                }
            }
        }
    }

    fun delete(objectiveId: String) {
        viewModelScope.launch {
            try {
                val resp = apiService.deleteObjective(objectiveId)
                if (resp.isSuccessful) {
                    load()
                }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }
}

/**
 * Card "Mes objectifs" affichee dans StatsScreen (v0.31). Liste les
 * objectifs actifs + progress + bouton "+" pour creer.
 */
@Composable
fun ObjectivesCard(
    viewModel: ObjectivesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Objective?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Mes objectifs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvel objectif")
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading && state.objectives.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                state.objectives.isEmpty() -> {
                    Text(
                        text = "Aucun objectif. Tape sur + pour en creer un (ex: 3 taches par semaine).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    state.objectives.forEachIndexed { index, obj ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        ObjectiveItem(
                            obj = obj,
                            onLongPress = { pendingDelete = obj },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateObjectiveDialog(
            isCreating = state.isCreating,
            onDismiss = { showCreate = false },
            onCreate = { title, period, target ->
                viewModel.create(title, period, target)
                showCreate = false
            },
        )
    }

    pendingDelete?.let { obj ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Supprimer cet objectif ?") },
            text = { Text("\"${obj.title}\" — cette action est definitive.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(obj.id)
                    pendingDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annuler") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObjectiveItem(obj: Objective, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = obj.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${obj.progressCount}/${obj.targetCount}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (obj.progressPercent >= 100)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(obj.progressPercent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = periodLabel(obj.period),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateObjectiveDialog(
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (title: String, period: String, target: Int) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("week") }
    var target by remember { mutableStateOf(3f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvel objectif") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Intitule") },
                    placeholder = { Text("Ex: Nettoyer la cuisine") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Periode", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("day" to "Par jour", "week" to "Par semaine", "month" to "Par mois")
                        .forEach { (k, label) ->
                            FilterChip(
                                selected = period == k,
                                onClick = { period = k },
                                label = { Text(label) },
                            )
                        }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Cible : ${target.roundToInt()} tache(s)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    valueRange = 1f..30f,
                    steps = 28,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, period, target.roundToInt()) },
                enabled = !isCreating && title.isNotBlank(),
            ) { Text("Creer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private fun periodLabel(period: String): String = when (period) {
    "day" -> "Par jour"
    "week" -> "Par semaine"
    "month" -> "Par mois"
    else -> period
}

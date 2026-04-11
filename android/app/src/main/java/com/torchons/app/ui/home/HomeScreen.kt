package com.torchons.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberDatePickerState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.TaskInProgress
import com.torchons.app.util.FreshnessCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    onTaskClick: (String) -> Unit = {},
    onEditTask: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val userId by viewModel.userId.collectAsState()
    val affinityEnabled by viewModel.affinityPopupEnabled.collectAsState()

    // Recharger au retour sur cet ecran
    LifecycleResumeEffect(Unit) {
        viewModel.loadData()
        onPauseOrDispose { }
    }

    // Popup affinite + effort (si active dans les reglages)
    if (affinityEnabled && uiState.showAffinityPopup && uiState.pendingAffinity != null) {
        val pending = uiState.pendingAffinity!!
        RatingPopup(
            pending = pending,
            onSubmit = { affinity, effort, recurrence ->
                viewModel.submitRating(pending.taskId, affinity, effort, recurrence)
            },
            onSubmitAndNext = { affinity, effort, recurrence ->
                viewModel.submitRatingAndNext(pending.taskId, affinity, effort, recurrence)
            },
            onDismiss = { viewModel.dismissAffinityPopup() }
        )
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Salutation + bouton noter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Bonjour ${displayName ?: ""} !",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.showManualRating() }) {
                Icon(
                    imageVector = Icons.Default.QuestionMark,
                    contentDescription = "Noter une tache",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        // Fair-share message
        val myFairShare = uiState.fairShare.find { it.userId == userId }
        val otherFairShare = uiState.fairShare.find { it.userId != userId }
        if (myFairShare != null && myFairShare.aboveTarget && otherFairShare != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = FreshnessCalculator.VERT.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = FreshnessCalculator.VERT,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Tu as fait plus que ta part ces 30 derniers jours ! C'est a ${otherFairShare.displayName} de faire des taches. Mais tu peux quand meme continuer !",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshnessCalculator.VERT
                    )
                }
            }
        }

        // Taches en cours
        if (uiState.inProgress.isNotEmpty()) {
            Text(
                text = "En cours",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            uiState.inProgress.forEach { tip ->
                InProgressCard(
                    tip = tip,
                    onClick = { onTaskClick(tip.taskId) },
                    onComplete = { viewModel.completeTask(tip.taskId) },
                    onCancel = { viewModel.cancelInProgress(tip.taskId) }
                )
            }
        }

        // Suggestions
        if (uiState.suggestions.isNotEmpty()) {
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            uiState.suggestions.forEach { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onClick = { onTaskClick(suggestion.taskId) },
                    onStart = { viewModel.startTask(suggestion.taskId) },
                    onComplete = { viewModel.completeTask(suggestion.taskId) },
                    onCompleteAtDate = { date, userId -> viewModel.completeTaskAtDate(suggestion.taskId, date, userId) },
                    onEdit = { onEditTask(suggestion.taskId) },
                    users = uiState.fairShare.map { it.userId to it.displayName }
                )
            }
        }

        if (uiState.suggestions.isEmpty() && uiState.inProgress.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = FreshnessCalculator.VERT
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Tout est frais !",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Aucune tache urgente pour le moment.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Version
        Text(
            text = "Torchons & Serviettes v${com.torchons.app.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onCompleteAtDate: (String, String?) -> Unit,
    onEdit: () -> Unit,
    users: List<Pair<String, String>> = emptyList()
) {
    val freshnessColor = FreshnessCalculator.getFreshnessColor(suggestion.freshness)
    val reasonLabel = if (suggestion.reason == "urgent") "La plus urgente" else "Tu aimes bien"
    val reasonIcon = if (suggestion.reason == "urgent") Icons.Default.Warning else Icons.Default.Favorite
    var showUserPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedUserId by remember { mutableStateOf<String?>(null) }

    // User picker dialog (qui a fait ?)
    if (showUserPicker) {
        AlertDialog(
            onDismissRequest = { showUserPicker = false },
            title = { Text("Qui a fait ?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    users.forEach { (uid, name) ->
                        Button(
                            onClick = {
                                selectedUserId = uid
                                showUserPicker = false
                                showDatePicker = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showUserPicker = false }) { Text("Annuler") }
            }
        )
    }

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { isoDate ->
                showDatePicker = false
                onCompleteAtDate(isoDate, selectedUserId)
                selectedUserId = null
            },
            onDismiss = { showDatePicker = false; selectedUserId = null }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge raison + bouton edit
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = reasonIcon,
                    contentDescription = null,
                    tint = if (suggestion.reason == "urgent") FreshnessCalculator.ROUGE else FreshnessCalculator.VERT,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (suggestion.reason == "urgent") FreshnessCalculator.ROUGE else FreshnessCalculator.VERT,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Modifier",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Barre de fraicheur
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (suggestion.freshness / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = freshnessColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${suggestion.freshness.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = freshnessColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Boutons - ligne 1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("En cours")
                }
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FreshnessCalculator.VERT)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fait !")
                }
            }

            // Bouton "Deja fait"
            OutlinedButton(
                onClick = { showUserPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Deja fait (choisir la date)")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val date = java.time.Instant.ofEpochMilli(millis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    // Envoyer comme ISO datetime a midi
                    onDateSelected("${date}T12:00:00Z")
                }
            }) {
                Text("Valider")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun InProgressCard(
    tip: TaskInProgress,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tip.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Commence le ${formatDateTime(tip.startedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pas fait")
                }
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FreshnessCalculator.VERT)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fait !")
                }
            }
        }
    }
}

/** Convertit des points effort (1-18) en texte temps lisible. */
private fun effortPointsToTime(points: Int): String {
    val minutes = points * 5
    return when {
        minutes >= 60 -> "${minutes / 60}h${if (minutes % 60 > 0) "%02d".format(minutes % 60) else ""}"
        else -> "${minutes}min"
    }
}

/** Resultat de la popup rating : affinity, effort, et update de recurrence optionnel. */
data class RatingRecurrence(
    val makeRecurring: Boolean = false,
    val everyNDays: Int = 7
)

@Composable
private fun RatingPopup(
    pending: PendingAffinity,
    onSubmit: (Int, Int, RatingRecurrence?) -> Unit,
    onSubmitAndNext: (Int, Int, RatingRecurrence?) -> Unit,
    onDismiss: () -> Unit
) {
    // Pre-remplir avec valeurs existantes ou defaut
    // Key sur taskId pour reinitialiser quand on change de tache
    var affinityValue by remember(pending.taskId) { mutableFloatStateOf((pending.currentAffinity ?: 5).toFloat()) }
    var effortValue by remember(pending.taskId) { mutableFloatStateOf((pending.currentEffortPoints ?: 3).toFloat()) }

    // Recurrence : analyser la rule existante
    val currentRecurrence = pending.recurrenceRule
    val isAlreadyInterval = currentRecurrence?.type == "interval"
    val currentDays = currentRecurrence?.everyNDays ?: 7

    var makeRecurring by remember(pending.taskId) { mutableStateOf(isAlreadyInterval) }
    // Slider va de 50% a 200% de la valeur actuelle si deja recurrente, sinon 1-30 jours
    val minDays = if (isAlreadyInterval) (currentDays * 0.5f).coerceAtLeast(1f) else 1f
    val maxDays = if (isAlreadyInterval) (currentDays * 2f).coerceAtLeast(2f) else 30f
    var daysValue by remember(pending.taskId) { mutableFloatStateOf(currentDays.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = pending.title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // === Affinite ===
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Affinite", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    com.torchons.app.ui.common.InfoTooltip(
                        title = "Affinite",
                        description = "Est-ce que tu aimes faire cette tache ? 1 = pas du tout, 10 = j'adore."
                    )
                }
                Text(
                    text = "${affinityValue.toInt()}/10",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Slider(
                    value = affinityValue,
                    onValueChange = { affinityValue = it },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pas du tout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("A fond !", style = MaterialTheme.typography.labelSmall, color = FreshnessCalculator.VERT)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Effort ===
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Effort", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    com.torchons.app.ui.common.InfoTooltip(
                        title = "Effort",
                        description = "Estimation du temps que prend cette tache. Sert a calculer les points."
                    )
                }
                Text(
                    text = effortPointsToTime(effortValue.toInt()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Slider(
                    value = effortValue,
                    onValueChange = { effortValue = it },
                    valueRange = 1f..12f,  // 5min a 1h
                    steps = 10
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("5min", style = MaterialTheme.typography.labelSmall)
                    Text("1h", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Frequence ===
                if (isAlreadyInterval) {
                    // Tache deja recurrente : ajuster la frequence
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Frequence : tous les ${daysValue.toInt()} jours", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.width(4.dp))
                        com.torchons.app.ui.common.InfoTooltip(
                            title = "Frequence",
                            description = "Ajuste la frequence de la tache. Tu peux modifier de 50% a 200% de la valeur actuelle ($currentDays jours)."
                        )
                    }
                    Slider(
                        value = daysValue,
                        onValueChange = { daysValue = it },
                        valueRange = minDays..maxDays
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${minDays.toInt()}j", style = MaterialTheme.typography.labelSmall)
                        Text("${maxDays.toInt()}j", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    // Tache ponctuelle : proposer de la rendre recurrente
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recurrente ?", style = MaterialTheme.typography.labelLarge)
                            Text("Faire revenir cette tache regulierement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = makeRecurring,
                            onCheckedChange = { makeRecurring = it }
                        )
                    }
                    if (makeRecurring) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tous les ${daysValue.toInt()} jours", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = daysValue,
                            onValueChange = { daysValue = it },
                            valueRange = 1f..30f,
                            steps = 28
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val rec = if (isAlreadyInterval || makeRecurring) RatingRecurrence(true, daysValue.toInt()) else null
                    onSubmitAndNext(affinityValue.toInt(), effortValue.toInt(), rec)
                }) { Text("Une autre !") }
                Button(onClick = {
                    val rec = if (isAlreadyInterval || makeRecurring) RatingRecurrence(true, daysValue.toInt()) else null
                    onSubmit(affinityValue.toInt(), effortValue.toInt(), rec)
                }) { Text("Ok") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Plus tard") }
        }
    )
}

private fun formatDateTime(isoDateTime: String): String {
    return try {
        val instant = try {
            Instant.parse(isoDateTime)
        } catch (e: Exception) {
            Instant.parse("${isoDateTime}Z")
        }
        val localDateTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("dd/MM 'a' HH:mm")
        localDateTime.format(formatter)
    } catch (e: Exception) {
        isoDateTime
    }
}

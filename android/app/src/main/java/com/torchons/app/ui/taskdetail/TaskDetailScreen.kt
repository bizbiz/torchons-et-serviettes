package com.torchons.app.ui.taskdetail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
import com.torchons.app.util.FreshnessCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ecran de detail d'une tache.
 * Affiche toutes les informations de la tache, sa fraicheur,
 * l'historique des completions et un bouton pour completer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    viewModel: TaskDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Etat du modal de profil utilisateur (ouvert au clic sur un nom).
    var userProfileRequest by remember { mutableStateOf<UserProfileRequest?>(null) }
    userProfileRequest?.let { req ->
        UserProfileDialog(
            request = req,
            onDismiss = { userProfileRequest = null },
            // Pas de nav depuis ici : on est deja sur une TaskDetail.
        )
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.completionMessage) {
        uiState.completionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCompletionMessage()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Dialog edition completion (qui + quand)
    if (uiState.editingCompletion != null) {
        val comp = uiState.editingCompletion!!
        var selectedUser by remember { mutableStateOf(comp.completedBy) }
        var showCompDatePicker by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(comp.completedAt) }

        if (showCompDatePicker) {
            val dps = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { showCompDatePicker = false },
                confirmButton = {
                    Button(onClick = {
                        dps.selectedDateMillis?.let { millis ->
                            val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            selectedDate = "${d}T12:00:00Z"
                        }
                        showCompDatePicker = false
                    }) { Text("Ok") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { showCompDatePicker = false }) { Text("Annuler") } }
            ) { DatePicker(state = dps) }
        }

        AlertDialog(
            onDismissRequest = { viewModel.dismissEditCompletion() },
            title = { Text("Modifier la completion", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Qui a fait ?", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.userNames.forEach { (uid, name) ->
                            val isSelected = uid == selectedUser
                            Button(
                                onClick = { selectedUser = uid },
                                colors = if (isSelected) ButtonDefaults.buttonColors()
                                    else ButtonDefaults.outlinedButtonColors(),
                                border = if (!isSelected) ButtonDefaults.outlinedButtonBorder(true) else null
                            ) { Text(name) }
                        }
                    }
                    Text("Quand ?", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { showCompDatePicker = true }) {
                        val dateStr = try {
                            val i = java.time.Instant.parse(selectedDate)
                            i.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        } catch (_: Exception) { selectedDate.take(10) }
                        Text(dateStr)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bouton supprimer
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteCompletion(comp.id)
                            viewModel.dismissEditCompletion()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Supprimer cette completion")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.saveCompletionEdit(comp.id, selectedUser, selectedDate) }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissEditCompletion() }) { Text("Annuler") }
            }
        )
    }

    // Modal de notation unifie (partage avec HomeScreen via ui/common/RatingDialog).
    // Ouvert depuis le bouton "Modifier" sur la card "Effort & Affinite".
    val ratingContext = uiState.ratingContext
    if (ratingContext != null) {
        RatingDialog(
            pending = ratingContext,
            onSubmit = { affinity, effort, recurrence ->
                viewModel.submitRating(affinity, effort, recurrence)
            },
            // Pas de chain "Une autre" depuis TaskDetail : on est sur UNE tache.
            onSubmitAndNext = null,
            onDismiss = { viewModel.dismissRatingDialog() },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.task?.title ?: "Detail",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { uiState.task?.let { onEdit(it.id) } }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Modifier",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.task == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.lastRefreshFailed && !uiState.hasCachedData) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Details indisponibles hors ligne",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Le backend ne repond pas pour le moment.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = "Tache introuvable",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            else -> {
                val task = uiState.task!!
                val freshnessColor = FreshnessCalculator.getFreshnessColor(uiState.freshness)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Section fraicheur
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(freshnessColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Fraicheur : ${FreshnessCalculator.getFreshnessLabel(uiState.freshness)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = freshnessColor
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "${uiState.freshness.toInt()}%",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = freshnessColor
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                LinearProgressIndicator(
                                    progress = { (uiState.freshness / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = freshnessColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                    }

                    // Section informations
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Description
                                if (!task.description.isNullOrBlank()) {
                                    Text(
                                        text = "Description",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = task.description,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // Effort & Affinite par user
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Effort & Affinite",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    androidx.compose.material3.TextButton(
                                        onClick = { viewModel.openRatingDialog(currentUserId) }
                                    ) { Text("Modifier") }
                                }
                                Spacer(modifier = Modifier.height(4.dp))

                                if (uiState.ratings.isNotEmpty()) {
                                    uiState.ratings.forEach { rating ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = rating.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.width(80.dp)
                                            )
                                            if (rating.effortTimeMin != null) {
                                                val timeLabel = if (rating.effortTimeMin >= 60) "${rating.effortTimeMin / 60}h${if (rating.effortTimeMin % 60 > 0) "%02d".format(rating.effortTimeMin % 60) else ""}" else "${rating.effortTimeMin}min"
                                                Text(
                                                    text = timeLabel,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.width(50.dp)
                                                )
                                            } else {
                                                Text("—", modifier = Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (rating.affinity != null) {
                                                Text(
                                                    text = "Aff: ${rating.affinity}/10",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else {
                                                Text("Aff: —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }

                                val avgMin = uiState.avgEffortTimeMin
                                if (avgMin != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val avgLabel = if (avgMin >= 60) "${avgMin / 60}h${if (avgMin % 60 > 0) "%02d".format(avgMin % 60) else ""}" else "${avgMin}min"
                                    Text(
                                        text = "Moyenne : $avgLabel → ${uiState.scorePoints ?: "?"} pts",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))

                                // Recurrence
                                if (task.recurrenceRule != null) {
                                    Text(
                                        text = "Recurrence",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatRecurrence(task.recurrenceRule),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // Tolerance au retard
                                Text(
                                    text = "Tolerance au retard",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formatTolerance(task.tolerance),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    // Bouton completer
                    item {
                        Button(
                            onClick = { viewModel.completeTask() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FreshnessCalculator.VERT
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Marquer comme fait",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Section historique
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Historique (${uiState.completions.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.completions.isEmpty()) {
                        item {
                            Text(
                                text = "Aucune completion pour cette tache",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(
                            items = uiState.completions,
                            key = { it.id }
                        ) { completion ->
                            CompletionCard(
                                completion = completion,
                                userName = uiState.userNames[completion.completedBy] ?: "?",
                                onDelete = { viewModel.deleteCompletion(completion.id) },
                                onEdit = { viewModel.showEditCompletion(completion) },
                                onUserClick = {
                                    val name = uiState.userNames[completion.completedBy] ?: "?"
                                    userProfileRequest = UserProfileRequest(
                                        userId = completion.completedBy,
                                        userName = name
                                    )
                                }
                            )
                        }
                    }

                    // Section historique des modifications (votes + edits)
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Historique des modifications (${uiState.history.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.history.isEmpty()) {
                        item {
                            Text(
                                text = "Aucune modification enregistree",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(
                            items = uiState.history,
                            key = { it.id }
                        ) { event ->
                            HistoryEventCard(event = event)
                        }
                    }

                    // Espace en bas
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

/** Carte compacte affichant un evenement de l'historique de modification. */
@Composable
private fun HistoryEventCard(event: TaskHistoryEvent) {
    val icon = when (event.type) {
        TaskHistoryEventType.VOTE_AFFINITY -> Icons.Default.Favorite
        TaskHistoryEventType.VOTE_EFFORT -> Icons.Default.Timer
        TaskHistoryEventType.VOTE_FREQUENCY -> Icons.Default.Schedule
        TaskHistoryEventType.TASK_UPDATE -> Icons.Default.Edit
        TaskHistoryEventType.UNKNOWN -> Icons.Default.History
    }
    val label = formatHistoryEvent(event)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (event.at != null) {
                    Text(
                        text = formatDateTime(event.at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formate un evenement d'historique en libelle francais.
 *
 * - VOTE_EFFORT : les points sont convertis en minutes (1pt = 5min)
 * - VOTE_FREQUENCY / VOTE_AFFINITY : valeurs affichees telles quelles
 * - TASK_UPDATE : liste des champs modifies (titre, recurrence, ...)
 */
private fun formatHistoryEvent(event: TaskHistoryEvent): String {
    val actor = event.actorName.ifBlank { "Quelqu'un" }
    return when (event.type) {
        TaskHistoryEventType.VOTE_FREQUENCY -> {
            val value = event.voteValue ?: return "$actor a vote une frequence"
            val consensus = event.consensusAfter
            if (consensus != null) {
                "$actor a vote ${value}j frequence → consensus ${consensus}j"
            } else {
                "$actor a vote ${value}j frequence"
            }
        }
        TaskHistoryEventType.VOTE_AFFINITY -> {
            val value = event.voteValue ?: return "$actor a vote une affinite"
            val consensus = event.consensusAfter
            if (consensus != null) {
                "$actor a vote ${value}/10 affinite → consensus ${consensus}/10"
            } else {
                "$actor a vote ${value}/10 affinite"
            }
        }
        TaskHistoryEventType.VOTE_EFFORT -> {
            val points = event.voteValue ?: return "$actor a vote un effort"
            val minutes = points * 5
            val consensusMin = event.consensusAfter?.let { it * 5 }
            val voteLabel = formatMinutes(minutes)
            if (consensusMin != null) {
                "$actor a vote $voteLabel effort → consensus ${formatMinutes(consensusMin)}"
            } else {
                "$actor a vote $voteLabel effort"
            }
        }
        TaskHistoryEventType.TASK_UPDATE -> {
            val fields = event.fieldsChanged.mapNotNull { frenchFieldName(it) }
            when {
                fields.isEmpty() -> "$actor a modifie la tache"
                fields.size == 1 -> "$actor a modifie ${fields[0]}"
                else -> "$actor a modifie ${fields.size} champs"
            }
        }
        TaskHistoryEventType.UNKNOWN -> "$actor a modifie la tache"
    }
}

private fun formatMinutes(min: Int): String =
    if (min >= 60) "${min / 60}h${if (min % 60 > 0) "%02d".format(min % 60) else ""}"
    else "${min}min"

private fun frenchFieldName(key: String): String? = when (key) {
    "title" -> "le titre"
    "description" -> "la description"
    "category_id" -> "la categorie"
    "tolerance" -> "la tolerance"
    "assigned_to_ids" -> "les affectations"
    "recurrence_rule" -> "la recurrence"
    "weight" -> "le poids"
    "is_active" -> "l'activation"
    "sort_order" -> "l'ordre"
    else -> null
}

/**
 * Carte affichant une completion dans l'historique.
 * Long press pour supprimer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompletionCard(
    completion: Completion,
    userName: String = "?",
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onUserClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer cette completion ?") },
            text = { Text("La completion du ${formatDateTime(completion.completedAt)} sera supprimee.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) { Text("Annuler") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEdit,
                onLongClick = { showDeleteDialog = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icone de validation
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(FreshnessCalculator.VERT.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = FreshnessCalculator.VERT
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Qui + date — le nom est cliquable vers le profil utilisateur.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { onUserClick() }
                    )
                    Text(
                        text = " — ${formatDateTime(completion.completedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Notes si presentes
                if (!completion.notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = completion.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Points gagnes
            Text(
                text = "+${completion.pointsEarned} pt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Formate une date ISO 8601 en format lisible. */
private fun formatDateTime(isoDateTime: String): String {
    return try {
        val instant = try {
            Instant.parse(isoDateTime)
        } catch (e: Exception) {
            Instant.parse("${isoDateTime}Z")
        }
        val localDateTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a' HH:mm")
        localDateTime.format(formatter)
    } catch (e: Exception) {
        isoDateTime
    }
}

/** Formate la regle de recurrence en texte lisible. */
private fun formatRecurrence(rule: com.torchons.app.data.model.RecurrenceRule): String {
    return when (rule.type) {
        "interval" -> "Tous les ${rule.everyNDays ?: "?"} jours"
        "weekdays" -> {
            val dayNames = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
            val days = rule.days?.map { dayNames.getOrElse(it - 1) { "?" } }?.joinToString(", ")
            "Jours : $days"
        }
        "monthly" -> "Mensuel (jour ${rule.dayOfMonth ?: "?"})"
        "custom" -> "Toutes les ${rule.everyNWeeks ?: "?"} semaines (jour ${rule.day ?: "?"})"
        else -> rule.type
    }
}

/** Formate la tolerance en texte lisible. */
private fun formatTolerance(tolerance: String): String {
    return when (tolerance) {
        "STRICT" -> "Strict"
        "NORMAL" -> "Normal"
        "SOUPLE" -> "Souple"
        else -> tolerance
    }
}

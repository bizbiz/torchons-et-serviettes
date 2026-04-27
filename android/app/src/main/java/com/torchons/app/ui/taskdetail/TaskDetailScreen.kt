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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.CompletionDialog
import com.torchons.app.ui.common.CompletionDialogMode
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.randomCelebration
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.TaskUrgencyCalculator
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
    onClone: (String) -> Unit = {},
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

    // Dialog unifie "Deja fait" / "Fait ensemble" (v0.11).
    // Utilise userNames comme source de verite pour les membres du foyer
    // (alimente par taskRepository.observeUsers() cote ViewModel).
    var completionDialogMode by remember { mutableStateOf<CompletionDialogMode?>(null) }
    var showTwoPersonFaitEnsembleDate by remember { mutableStateOf(false) }

    completionDialogMode?.let { mode ->
        val task = uiState.task
        val allMembers = uiState.userNames.entries.map { it.key to it.value }
        val assignedIds = task?.assignedToIds.orEmpty().toSet()
        CompletionDialog(
            taskTitle = task?.title.orEmpty(),
            mode = mode,
            allMembers = allMembers,
            assignedMemberIds = assignedIds,
            currentUserId = currentUserId.orEmpty(),
            onDismiss = { completionDialogMode = null },
            onConfirm = { participantIds, isoDate, _ ->
                if (mode == CompletionDialogMode.Single) {
                    viewModel.completeTaskAtDate(isoDate, participantIds.firstOrNull())
                } else {
                    viewModel.completeTaskCollective(participantIds, isoDate)
                }
                completionDialogMode = null
            },
            onSwitchToMulti = if (mode == CompletionDialogMode.Single) {
                { completionDialogMode = CompletionDialogMode.Multi }
            } else null,
            previewProvider = task?.let { t ->
                { isoDate, numParticipants ->
                    viewModel.fetchCompletionPreview(t.id, isoDate, numParticipants)
                }
            },
        )
    }

    // Foyer 2-pers : DatePickerDialog direct pour "Fait ensemble" (split auto).
    if (showTwoPersonFaitEnsembleDate) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showTwoPersonFaitEnsembleDate = false },
            confirmButton = {
                Button(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        val bothIds = uiState.userNames.keys.toList()
                        viewModel.completeTaskCollective(bothIds, "${date}T12:00:00Z")
                    }
                    showTwoPersonFaitEnsembleDate = false
                }) { Text("Valider") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTwoPersonFaitEnsembleDate = false }) {
                    Text("Annuler")
                }
            },
        ) { DatePicker(state = datePickerState) }
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
        // Resoud le libelle de la piece a passer au modal — uiState.room est
        // deja resolu par le ViewModel.
        val ratingRoomLabel = uiState.room?.let { r ->
            if (!r.emoji.isNullOrBlank()) "${r.emoji} ${r.name}" else r.name
        }
        RatingDialog(
            pending = ratingContext,
            onSubmit = { affinity, effort, recurrence ->
                viewModel.submitRating(affinity, effort, recurrence)
            },
            // Pas de chain "Une autre" depuis TaskDetail : on est sur UNE tache.
            onSubmitAndNext = null,
            onDismiss = { viewModel.dismissRatingDialog() },
            roomLabel = ratingRoomLabel,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    IconButton(onClick = { uiState.task?.let { onClone(it.id) } }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Dupliquer cette tache",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
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
                val stateColor = TaskUrgencyCalculator.stateColor(uiState.urgencyState)
                val stateLabel = when (uiState.urgencyState) {
                    TaskUrgencyCalculator.State.GREEN -> "A jour"
                    TaskUrgencyCalculator.State.ORANGE -> "A faire bientot"
                    TaskUrgencyCalculator.State.RED -> "En retard"
                }

                val assignedIds = task.assignedToIds.toSet()
                val assignedNames = task.assignedToIds
                    .mapNotNull { uiState.userNames[it] }
                    .distinct()
                val isCurrentUserAssigned = currentUserId in assignedIds
                val allFoyerAssigned = uiState.userNames.isNotEmpty() &&
                    uiState.userNames.keys.all { it in assignedIds }
                val assignedLabel = when {
                    assignedNames.isEmpty() -> null
                    allFoyerAssigned -> "Pour tout le foyer"
                    assignedNames.size == 1 -> "Pour ${assignedNames.single()} uniquement"
                    else -> "Pour ${assignedNames.joinToString(", ")}"
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Bandeau "Pour : <users assignes>" pour lever toute ambiguite
                    // sur qui est concerne par la tache. Coherent avec la gate
                    // cote backend : seuls ces users voient les prompts d'affinite
                    // et peuvent voter (cf _ensure_user_assigned).
                    if (assignedLabel != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = assignedLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    // Section etat d'urgence (couleur + countdown)
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
                                            .background(stateColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stateLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = stateColor
                                    )
                                }
                                // Pastille bonus/malus a cote du label urgence
                                uiState.bonusPreview?.let { preview ->
                                    if (preview.reason == "BONUS_RETARD") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "⭐ +${(preview.multiplier * 100 - 100).toInt()}% bonus",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color(0xFF2E7D32),
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            // TODO : tooltip "i" avec explication seuils
                                        }
                                    } else if (preview.reason == "MALUS_AVANCE") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "⏱ -${(100 - preview.multiplier * 100).toInt()}% malus",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color(0xFFEF6C00),
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                                if (uiState.countdownLabel.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = uiState.countdownLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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

                                // Piece (read-only — l'edition se fait via le crayon top-bar)
                                if (uiState.room != null) {
                                    Text(
                                        text = "Piece",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val r = uiState.room!!
                                    val label = if (!r.emoji.isNullOrBlank()) "${r.emoji} ${r.name}" else r.name
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // Effort & Affinite par user. Bouton "Modifier"
                                // uniquement pour les users assignes (coherent avec
                                // la gate backend _ensure_user_assigned).
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Effort & Affinite",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isCurrentUserAssigned) {
                                        androidx.compose.material3.TextButton(
                                            onClick = { viewModel.openRatingDialog(currentUserId) }
                                        ) { Text("Modifier") }
                                    }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Tolerance au retard",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    com.torchons.app.ui.common.InfoTooltip(
                                        title = "A quoi sert la tolerance ?",
                                        description = com.torchons.app.ui.common.TOLERANCE_INFO_TEXT,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formatTolerance(task.tolerance),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    // Bouton completer (ou celebration si la tache vient
                    // d'etre validee — confetti gere au niveau du Box parent).
                    // 3 actions en colonne : Fait (principal) + Deja fait +
                    // Fait ensemble (secondaires).
                    item {
                        if (uiState.isCelebrating) {
                            val celebration = remember(uiState.celebrationTrigger) {
                                randomCelebration()
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = TaskUrgencyCalculator.VERT.copy(alpha = 0.15f)
                                ),
                            ) {
                                CelebrationCardContent(
                                    emoji = celebration.first,
                                    text = celebration.second,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.completeTask() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TaskUrgencyCalculator.VERT
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
                                // Secondaires "Deja fait" + "Fait ensemble" cote a cote
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { completionDialogMode = CompletionDialogMode.Single },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Deja fait")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (uiState.userNames.size == 2) {
                                                showTwoPersonFaitEnsembleDate = true
                                            } else {
                                                completionDialogMode = CompletionDialogMode.Multi
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Groups,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ensemble")
                                    }
                                }
                            }
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
                        // Grouping par collective_id : un evenement collectif =
                        // une seule carte. Solo : carte = 1 row comme avant.
                        // Pas de `remember` ici (LazyListScope n'est pas
                        // @Composable) — le groupBy sur une liste courte est
                        // negligeable en cout.
                        val groups = groupCompletions(uiState.completions)
                        items(
                            items = groups,
                            key = { it.key }
                        ) { group ->
                            CompletionCard(
                                group = group,
                                userNames = uiState.userNames,
                                onDeleteOne = { viewModel.deleteCompletion(group.head.id) },
                                onDeleteCollective = {
                                    group.collectiveId?.let { viewModel.deleteCollective(it) }
                                },
                                onEdit = { viewModel.showEditCompletion(group.head) },
                                onUserClick = { uid ->
                                    val name = uiState.userNames[uid] ?: "?"
                                    userProfileRequest = UserProfileRequest(
                                        userId = uid,
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

    // Overlay konfetti plein ecran pendant la celebration.
    androidx.compose.runtime.key(uiState.celebrationTrigger) {
        CelebrationOverlay(isCelebrating = uiState.isCelebrating)
    }
    } // fin du Box outer (celebration overlay layer)
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
 * Groupe de completions partageant un collective_id (1 ligne dans l'UI = 1
 * evenement). `head` est la premiere ligne triee chronologique desc — sert
 * de reference pour la date, le score, les notes. `participantIds` enumere
 * les participants dans l'ordre de saisie.
 */
data class CompletionGroup(
    val key: String,
    val collectiveId: String?,
    val head: Completion,
    val rows: List<Completion>,
)

/** Joint une liste de noms en francais : "A, B & C" — virgules + esperluette finale. */
private fun joinWithAmp(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
}

/**
 * Regroupe une liste de Completion par collective_id. Les lignes solo gardent
 * leur cle individuelle. Le head de chaque groupe = la row dont l'id est
 * minimal lexicalement (deterministe ; toutes partagent le meme completed_at).
 */
private fun groupCompletions(completions: List<Completion>): List<CompletionGroup> {
    val grouped = LinkedHashMap<String, MutableList<Completion>>()
    for (c in completions) {
        val key = c.collectiveId ?: "solo:${c.id}"
        grouped.getOrPut(key) { mutableListOf() }.add(c)
    }
    return grouped.map { (key, rows) ->
        val sorted = rows.sortedBy { it.id }
        CompletionGroup(
            key = key,
            collectiveId = sorted.first().collectiveId,
            head = sorted.first(),
            rows = sorted,
        )
    }
}

/**
 * Carte affichant une completion (ou un groupe collectif) dans l'historique.
 * Solo : tap = edit, long-press = delete-one. Collectif : tap = edit head,
 * long-press = delete-collective (toutes les lignes du collective_id).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompletionCard(
    group: CompletionGroup,
    userNames: Map<String, String>,
    onDeleteOne: () -> Unit,
    onDeleteCollective: () -> Unit,
    onEdit: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val isCollective = group.collectiveId != null && group.rows.size >= 2
    val head = group.head
    val participantNames = group.rows.map { userNames[it.completedBy] ?: "?" }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (isCollective) "Supprimer ce collectif ?"
                    else "Supprimer cette completion ?"
                )
            },
            text = {
                if (isCollective) {
                    Text(
                        "Le collectif (${joinWithAmp(participantNames)}) du " +
                                "${formatDateTime(head.completedAt)} sera entierement supprime."
                    )
                } else {
                    Text("La completion du ${formatDateTime(head.completedAt)} sera supprimee.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        if (isCollective) onDeleteCollective() else onDeleteOne()
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
            // Icone : check pour solo, groupe pour collectif.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(TaskUrgencyCalculator.VERT.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCollective) Icons.Default.Groups else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TaskUrgencyCalculator.VERT
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // AnnotatedString unique : noms cliquables (couleur primaire +
                // souligne) + date inline. Une seule ligne de texte → baseline
                // coherente entre les noms et le reste, plus de decalage vertical.
                val primaryColor = MaterialTheme.colorScheme.primary
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val rowsForKey = group.rows
                val annotated = remember(rowsForKey, userNames, head.completedAt, primaryColor, onSurfaceColor) {
                    buildAnnotatedString {
                        addStyle(SpanStyle(color = onSurfaceColor), 0, 0)
                        if (isCollective) {
                            rowsForKey.forEachIndexed { idx, row ->
                                val name = userNames[row.completedBy] ?: "?"
                                if (idx > 0) {
                                    append(if (idx == rowsForKey.size - 1) " & " else ", ")
                                }
                                val start = length
                                append(name)
                                addStyle(
                                    SpanStyle(
                                        color = primaryColor,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    start, length,
                                )
                                addStringAnnotation("user", row.completedBy, start, length)
                            }
                        } else {
                            val name = userNames[head.completedBy] ?: "?"
                            val start = length
                            append(name)
                            addStyle(
                                SpanStyle(
                                    color = primaryColor,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                start, length,
                            )
                            addStringAnnotation("user", head.completedBy, start, length)
                        }
                        append(" — ${formatDateTime(head.completedAt)}")
                    }
                }
                ClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(color = onSurfaceColor),
                    onClick = { offset ->
                        val ann = annotated.getStringAnnotations(
                            tag = "user", start = offset, end = offset
                        ).firstOrNull()
                        if (ann != null) {
                            onUserClick(ann.item)
                        } else {
                            onEdit()
                        }
                    },
                )

                if (!head.notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = head.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Points gagnes : "+X pt" solo / "+X pt chacun" collectif.
            Text(
                text = if (isCollective) "+${head.pointsEarned} pt chacun"
                else "+${head.pointsEarned} pt",
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
        "interval" -> rule.everyNDays?.let { "Tous les ${DurationFormat.formatDaysHuman(it, long = true)}" } ?: "Tous les ? jours"
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

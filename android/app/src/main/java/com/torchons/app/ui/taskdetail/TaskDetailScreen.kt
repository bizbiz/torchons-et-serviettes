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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.QuestionMark
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import com.torchons.app.ui.common.ImeSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.torchons.app.ui.common.PrimaryTopBar
import com.torchons.app.ui.common.showUserError
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.RecurrenceTypes
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.today.rememberTodayActionState
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.common.rememberCelebration
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
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
    val gamificationVisible by viewModel.gamificationVisible.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // Etat du modal de profil utilisateur (ouvert au clic sur un nom).
    var userProfileRequest by remember { mutableStateOf<UserProfileRequest?>(null) }
    userProfileRequest?.let { req ->
        UserProfileDialog(
            request = req,
            onDismiss = { userProfileRequest = null },
            // Pas de nav depuis ici : on est deja sur une TaskDetail.
        )
    }

    // « Date + Qui » and « ! » (TaskDetailTodayAndWho.kt, docs/plans/a-faire-aujourdhui.md).
    // null = closed; false = « Déjà fait » (me alone checked); true = « Fait ensemble ».
    var completionTogether by remember { mutableStateOf<Boolean?>(null) }
    val todayActions = rememberTodayActionState()
    completionTogether?.let { together ->
        TaskDetailCompletionDialog(viewModel, currentUserId.orEmpty(), gamificationVisible, together) {
            completionTogether = null
        }
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
            snackbarHostState.showUserError(appContext, it, "TaskDetail")
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
                    }) { Text(stringResource(R.string.common_ok)) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { showCompDatePicker = false }) { Text(stringResource(R.string.common_cancel)) } }
            ) { DatePicker(state = dps) }
        }

        AlertDialog(
            onDismissRequest = { viewModel.dismissEditCompletion() },
            title = { Text(stringResource(R.string.task_detail_edit_completion_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.task_detail_edit_who), style = MaterialTheme.typography.labelLarge)
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
                    Text(stringResource(R.string.task_detail_edit_when), style = MaterialTheme.typography.labelLarge)
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
                        Text(stringResource(R.string.task_detail_edit_delete_button))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.saveCompletionEdit(comp.id, selectedUser, selectedDate) }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissEditCompletion() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // v0.28 — Modaux "+ d'info" frequence / affinite, FrequencyVoteDialog
    // et EffortVoteDialog ouverts depuis le bandeau ou les sous-textes.
    VoteInfoDialogs(uiState, currentUserId, gamificationVisible, viewModel)
    if (uiState.showAffinityInfo) {
        val rows = uiState.ratings.mapNotNull { r ->
            r.userId?.let {
                com.torchons.app.ui.common.AffinityRatingRow(
                    userId = it,
                    displayName = r.displayName,
                    affinity = r.affinity,
                )
            }
        }
        com.torchons.app.ui.common.AffinityInfoDialog(
            ratings = rows,
            currentUserId = currentUserId,
            onEditMyAffinity = {
                viewModel.dismissAffinityInfo()
                viewModel.openRatingDialog(currentUserId)
            },
            onDismiss = { viewModel.dismissAffinityInfo() },
        )
    }
    if (uiState.showFrequencyVoteDialog && uiState.frequencyVotes != null && uiState.task != null) {
        com.torchons.app.ui.common.FrequencyVoteDialog(
            header = com.torchons.app.ui.common.voteHeader(uiState.task!!),
            state = uiState.frequencyVotes!!,
            onSubmit = { req -> viewModel.submitFrequencyVote(req) },
            onDismiss = { viewModel.dismissFrequencyVoteDialog() },
        )
    }
    if (uiState.showEffortVoteDialog && uiState.effortVotes != null && uiState.task != null) {
        com.torchons.app.ui.common.EffortVoteDialog(
            header = com.torchons.app.ui.common.voteHeader(uiState.task!!),
            state = uiState.effortVotes!!,
            onSubmit = { points -> viewModel.submitEffortVote(points) },
            onDismiss = { viewModel.dismissEffortVoteDialog() },
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
        snackbarHost = { ImeSnackbarHost(snackbarHostState) },
        topBar = {
            PrimaryTopBar(
                title = {
                    Text(
                        text = uiState.task?.title ?: stringResource(R.string.task_detail_title_fallback),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { uiState.task?.let { onClone(it.id) } }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.task_detail_clone_cd),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { uiState.task?.let { onEdit(it.id) } }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.task_detail_edit_cd),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    HelpButton(HelpScreen.TASK_DETAIL, tint = MaterialTheme.colorScheme.onPrimary)
                },
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
                                text = stringResource(R.string.task_detail_offline_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.task_detail_offline_message),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.task_detail_not_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            else -> {
                val task = uiState.task!!
                val stateColor = taskPageStateColor(uiState.urgencyState, task.recurrenceRule)
                val stateLabel = when (uiState.urgencyState) {
                    TaskUrgencyCalculator.State.GREEN -> stringResource(R.string.task_detail_state_green)
                    TaskUrgencyCalculator.State.ORANGE -> stringResource(R.string.task_detail_state_orange)
                    TaskUrgencyCalculator.State.RED -> stringResource(R.string.task_detail_state_red)
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
                    allFoyerAssigned -> stringResource(R.string.task_detail_assigned_all)
                    assignedNames.size == 1 -> stringResource(R.string.task_detail_assigned_one_format, assignedNames.single())
                    else -> stringResource(R.string.task_detail_assigned_many_format, assignedNames.joinToString(", "))
                }

                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh(userInitiated = true) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
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
                                uiState.bonusPreview?.takeIf { gamificationVisible }?.let { preview ->
                                    if (preview.reason == "BONUS_RETARD") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stringResource(R.string.task_detail_bonus_format, (preview.multiplier * 100 - 100).toInt()),
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
                                                text = stringResource(R.string.task_detail_malus_format, (100 - preview.multiplier * 100).toInt()),
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

                    // v0.28 — Bandeau "Tu n'as pas vote" (decisions design review Q8).
                    // Visible si user assigne ET au moins un des 3 votes manque.
                    // Click ouvre RatingDialog (skip auto les sliders deja votes).
                    val rule = task.recurrenceRule
                    val isVotableType = RecurrenceTypes.isVotable(rule)
                    val freqMissing = isVotableType && uiState.frequencyVotes?.myDaysPerMonth == null
                    val myRow = uiState.ratings.firstOrNull { it.userId == currentUserId }
                    val affMissing = myRow?.affinity == null
                    val effortMissing = uiState.effortVotes?.myPoints == null
                    val anyMissing = freqMissing || affMissing || effortMissing
                    val canVoteHere = currentUserId != null && currentUserId in assignedIds && uiState.task?.isActive == true
                    if (anyMissing && canVoteHere) {
                        item {
                            val missingFreq = stringResource(R.string.task_detail_missing_frequency)
                            val missingAff = stringResource(R.string.task_detail_missing_affinity)
                            val missingEff = stringResource(R.string.task_detail_missing_effort)
                            val missing = buildList {
                                if (freqMissing) add(missingFreq)
                                if (affMissing) add(missingAff)
                                if (effortMissing) add(missingEff)
                            }
                            val touchLabel = pluralStringResource(R.plurals.task_detail_no_vote_touch, missing.size.coerceAtLeast(1))
                            val label = if (missing.size == 1)
                                stringResource(R.string.task_detail_no_vote_one_format, missing[0])
                            else stringResource(R.string.task_detail_no_vote_many)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openRatingDialog(currentUserId) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QuestionMark,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            text = touchLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
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
                                        text = stringResource(R.string.task_detail_section_description),
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
                                        text = stringResource(R.string.task_detail_section_room),
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

                                // Effort & Affinite par user (TaskRatingsSection.kt).
                                // Affinity and effort ratings belong to `repartition`: hidden with it.
                                if (moduleEnabled(Modules.REPARTITION)) {
                                    TaskRatingsSection(uiState, isCurrentUserAssigned, currentUserId, viewModel)
                                }

                                // Recurrence
                                if (task.recurrenceRule != null) {
                                    Text(
                                        text = stringResource(R.string.task_detail_section_frequency),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatRecurrence(task.recurrenceRule, task.inferredIntervalDays),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    // v0.28 — sous-texte adaptatif des votes + lien "+ d'info".
                                    val freqVotes = uiState.frequencyVotes
                                    val isVotableRule = RecurrenceTypes.isVotable(task.recurrenceRule)
                                    if (freqVotes != null && isVotableRule) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val youPronoun = stringResource(R.string.proposition_receiver_you_suffix).trim().trimStart('(').trimEnd(')')
                                        val voterNames = buildList<String> {
                                            if (freqVotes.myDaysPerMonth != null) add(youPronoun)
                                            addAll(freqVotes.others.map { it.displayName })
                                        }
                                        val nonVoterNames = task.assignedToIds
                                            .filterNot { it == currentUserId && freqVotes.myDaysPerMonth != null }
                                            .filterNot { uid -> freqVotes.others.any { it.userId == uid } }
                                            .map { uid ->
                                                if (uid == currentUserId) youPronoun
                                                else (uiState.userNames[uid] ?: "?")
                                            }
                                        val summary = when {
                                            voterNames.size == 1 -> stringResource(R.string.task_detail_freq_vote_one_format, voterNames[0])
                                            voterNames.size > 1 -> stringResource(R.string.task_detail_freq_vote_many_format, joinFr(voterNames))
                                            else -> stringResource(R.string.task_detail_freq_no_vote)
                                        }
                                        val nonVoteSuffix = if (nonVoterNames.isNotEmpty()) {
                                            val phrase = if (nonVoterNames.singleOrNull() == youPronoun) {
                                                stringResource(R.string.task_detail_freq_you_not_voted)
                                            } else pluralStringResource(
                                                R.plurals.task_detail_freq_no_voters_format,
                                                nonVoterNames.size,
                                                joinFr(nonVoterNames),
                                            )
                                            stringResource(R.string.task_detail_no_voters_suffix_format, phrase)
                                        } else ""
                                        val moreInfoFreq = stringResource(R.string.task_detail_more_info_link)
                                        val sublineFreq = stringResource(R.string.task_detail_freq_subline_format, summary, nonVoteSuffix)
                                        androidx.compose.foundation.text.ClickableText(
                                            text = androidx.compose.ui.text.buildAnnotatedString {
                                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                                    append(sublineFreq)
                                                }
                                                withStyle(SpanStyle(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    textDecoration = TextDecoration.Underline,
                                                )) {
                                                    append(moreInfoFreq)
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            onClick = { viewModel.openFrequencyInfo() },
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // Tolerance au retard
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.task_detail_section_tolerance),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    com.torchons.app.ui.common.InfoTooltip(
                                        title = stringResource(R.string.task_detail_tolerance_help_title),
                                        description = com.torchons.app.ui.common.toleranceInfoText(),
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
                            val celebration = rememberCelebration(uiState.celebrationTrigger)
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
                                    // Points + attribution masques si gamif opt-out (v0.32).
                                    points = if (gamificationVisible) uiState.celebrationPoints else null,
                                    reason = uiState.celebrationReason,
                                    earnedBy = if (gamificationVisible) uiState.celebrationEarnedBy else null,
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
                                        text = stringResource(R.string.task_detail_mark_done),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                // Secondaires « Deja fait » (Date + Qui) + « Fait ensemble », puis le « ! »
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { completionTogether = false },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.suggestion_action_already_done))
                                    }
                                    if (uiState.task?.assignedToIds?.size != 1) OutlinedButton( // not for a solo task
                                        onClick = { completionTogether = true },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Groups,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.suggestion_action_together))
                                    }
                                }
                                TaskDetailTodayButton(
                                    viewModel, currentUserId.orEmpty(), todayActions, Modifier.fillMaxWidth(),
                                )
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
                                text = stringResource(R.string.task_detail_history_title_format, uiState.completions.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.completions.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.task_detail_history_empty),
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
                                formerMembers = uiState.formerMembers, showPoints = gamificationVisible,
                                onDeleteOne = { viewModel.deleteCompletion(group.head.id) },
                                onDeleteCollective = {
                                    viewModel.deleteCollective(group.rows.map { it.id })
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
                                text = stringResource(R.string.task_detail_modifications_title_format, uiState.history.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.history.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.task_detail_modifications_empty),
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

    // Overlay konfetti plein ecran pendant la celebration.
    androidx.compose.runtime.key(uiState.celebrationTrigger) {
        CelebrationOverlay(isCelebrating = uiState.isCelebrating)
    }
    } // fin du Box outer (celebration overlay layer)
}

@Composable
internal fun formatMinutes(min: Int): String =
    if (min >= 60) {
        if (min % 60 > 0) stringResource(R.string.duration_hours_minutes_format, min / 60, min % 60)
        else stringResource(R.string.duration_hours_only_format, min / 60)
    } else stringResource(R.string.duration_minutes_format, min)

/** Formate une date ISO 8601 en format lisible (pattern localisé via stringResource). */
@Composable
internal fun formatDateTime(isoDateTime: String): String {
    val pattern = stringResource(R.string.date_with_time_pattern)
    return try {
        val instant = try {
            Instant.parse(isoDateTime)
        } catch (e: Exception) {
            Instant.parse("${isoDateTime}Z")
        }
        val localDateTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern(pattern)
        localDateTime.format(formatter)
    } catch (e: Exception) {
        isoDateTime
    }
}

/** Joint une liste de noms en francais : "A, B & C". */
private fun joinFr(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
}

/** Formate la tolerance en texte lisible. */
@Composable
private fun formatTolerance(tolerance: String): String {
    return when (tolerance) {
        "STRICT" -> stringResource(R.string.task_tolerance_strict)
        "NORMAL" -> stringResource(R.string.task_tolerance_normal)
        "SOUPLE" -> stringResource(R.string.task_tolerance_souple)
        else -> tolerance
    }
}

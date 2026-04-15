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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.rememberDatePickerState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.TaskInProgress
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.ui.common.randomCelebration
import com.torchons.app.util.TaskUrgencyCalculator
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
    val rooms by viewModel.rooms.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val toastContext = LocalContext.current

    // Maps de lookup memoisees pour pouvoir resolver task -> room/category sans
    // refaire .find() a chaque card.
    val tasksById = remember(activeTasks) { activeTasks.associateBy { it.id } }
    val roomsById = remember(rooms) { rooms.associateBy { it.id } }
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    // Recharger au retour sur cet ecran + report de presence WiFi pour le
    // home freeze (v0.7.4). Le report sert aussi a recevoir un welcome_back
    // si on revient apres > 24h d'absence. Refresh aussi les propositions
    // actives en poll de fallback si le FCM push etait rate.
    LifecycleResumeEffect(Unit) {
        viewModel.loadData()
        viewModel.reportPresenceNow()
        viewModel.refreshPendingProposition()
        onPauseOrDispose { }
    }

    // Popup "bon retour" apres une absence > 24h (home freeze v0.7.4)
    uiState.welcomeBack?.let { wb ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissWelcomeBack() },
            title = {
                Text("Bon retour a la maison !", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Tu as ete absent ${wb.absenceDays} jour${if (wb.absenceDays > 1) "s" else ""}. " +
                        "Pendant ton absence, les taches \"a la maison\" ont ete mises en pause " +
                        "(au-dela de 24h). Elles n'ont donc pas pris de retard !"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWelcomeBack() }) {
                    Text("Super !")
                }
            },
        )
    }

    // Popup affinite + effort (si active dans les reglages)
    if (affinityEnabled && uiState.showAffinityPopup && uiState.pendingAffinity != null) {
        val pending = uiState.pendingAffinity!!
        // Resoudre le label de la piece via le cache : task -> roomId -> Room
        val pendingTask = tasksById[pending.taskId]
        val pendingRoom = pendingTask?.roomId?.let { roomsById[it] }
        val pendingRoomLabel = pendingRoom?.let { r ->
            if (!r.emoji.isNullOrBlank()) "${r.emoji} ${r.name}" else r.name
        }
        RatingDialog(
            pending = pending,
            onSubmit = { affinity, effort, recurrence ->
                viewModel.submitRating(pending.taskId, affinity, effort, recurrence)
            },
            onSubmitAndNext = { affinity, effort, recurrence ->
                viewModel.submitRatingAndNext(pending.taskId, affinity, effort, recurrence)
            },
            onDismiss = { viewModel.dismissAffinityPopup() },
            roomLabel = pendingRoomLabel,
        )
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Popup simulation projection (v0.8.0 magic buttons, multi-slider v0.8.1)
    var showProjectionDialog by remember { mutableStateOf(false) }
    val currentProposition by viewModel.currentProposition.collectAsState()
    val propositionCelebration by viewModel.propositionCelebration.collectAsState()
    val propositionError by viewModel.propositionError.collectAsState()

    // Toast pour les erreurs propositions (expiration, decline, ...)
    LaunchedEffect(propositionError) {
        propositionError?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissPropositionError()
        }
    }

    // Simulation dialog : ouvert par tap sur les cards projection. Hidden tant
    // qu'une proposition est active (le flow live prend le pas).
    if (showProjectionDialog && currentProposition == null && propositionCelebration == null) {
        ProjectionSimulationDialog(
            pace = uiState.pace,
            retard = uiState.retard,
            maintenance = uiState.maintenance,
            currentUserId = userId,
            onDismiss = { showProjectionDialog = false },
            onSaveSelf = { minutes, unit ->
                viewModel.saveObjective(minutes, unit)
                showProjectionDialog = false
            },
            onProposeToFoyer = { sliderValues, unit ->
                viewModel.proposeToFoyer(sliderValues, unit)
                showProjectionDialog = false
            },
        )
    }

    // Flow live propositions (v0.8.1) : afficher proposer-waiting ou
    // receiver-modal selon qui est l'user courant.
    currentProposition?.let { prop ->
        if (prop.proposerId == userId) {
            PropositionProposerWaitingDialog(
                proposition = prop,
                onCancel = { viewModel.cancelProposition(prop.id) },
            )
        } else {
            PropositionReceiverDialog(
                proposition = prop,
                currentUserId = userId,
                onAccept = { viewModel.respondToProposition(prop.id, true) },
                onDecline = { viewModel.respondToProposition(prop.id, false) },
            )
        }
    }

    propositionCelebration?.let { prop ->
        PropositionCelebrationDialog(
            proposition = prop,
            currentUserId = userId,
            onDismiss = { viewModel.dismissPropositionCelebration() },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        // 2 cards projection (v0.8.0 magic buttons)
        ProjectionCardsRow(
            pace = uiState.pace,
            retard = uiState.retard,
            maintenance = uiState.maintenance,
            currentUserId = userId,
            onCardClick = { showProjectionDialog = true },
        )

        // Taches en cours
        if (uiState.inProgress.isNotEmpty()) {
            Text(
                text = "En cours",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            uiState.inProgress.forEach { tip ->
                val task = tasksById[tip.taskId]
                val room = task?.roomId?.let { roomsById[it] }
                val category = task?.categoryId?.let { categoriesById[it] }
                InProgressCard(
                    tip = tip,
                    room = room,
                    category = category,
                    isCelebrating = uiState.celebratingTaskId == tip.taskId,
                    onClick = { onTaskClick(tip.taskId) },
                    onComplete = { viewModel.completeTask(tip.taskId) },
                    onCancel = { viewModel.cancelInProgress(tip.taskId) }
                )
            }
        }

        // Suggestions (titre retire v0.8.0 — evident que c'est des suggestions)
        if (uiState.suggestions.isNotEmpty()) {
            uiState.suggestions.forEach { suggestion ->
                val task = tasksById[suggestion.taskId]
                val room = task?.roomId?.let { roomsById[it] }
                val category = task?.categoryId?.let { categoriesById[it] }
                SuggestionCard(
                    suggestion = suggestion,
                    room = room,
                    category = category,
                    isCelebrating = uiState.celebratingTaskId == suggestion.taskId,
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
                // Distinguer "vraiment rien (tout est frais)" de
                // "rien parce que le backend n'a jamais repondu et le cache est vide".
                val backendUnreachable = uiState.lastRefreshFailed && !uiState.hasCachedData
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (backendUnreachable) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Donnees indisponibles",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Le backend ne repond pas pour le moment. Reessaie dans quelques instants.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TaskUrgencyCalculator.VERT
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

    // Overlay confetti plein ecran — declenche par celebrationTrigger et
    // visible tant que celebratingTaskId est non-null. La key force konfetti
    // a relancer une nouvelle vague a chaque celebration.
    androidx.compose.runtime.key(uiState.celebrationTrigger) {
        CelebrationOverlay(isCelebrating = uiState.celebratingTaskId != null)
    }
    } // fin du Box outer (celebration overlay layer)
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    room: Room?,
    category: Category?,
    isCelebrating: Boolean,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onCompleteAtDate: (String, String?) -> Unit,
    onEdit: () -> Unit,
    users: List<Pair<String, String>> = emptyList()
) {
    val reasonLabel = if (suggestion.reason == "urgent") "La plus urgente" else "Tu aimes bien"
    val reasonIcon = if (suggestion.reason == "urgent") Icons.Default.Warning else Icons.Default.Favorite
    // Pickee une seule fois pour cette celebration (key suggestion.taskId).
    val celebration = remember(suggestion.taskId, isCelebrating) {
        if (isCelebrating) randomCelebration() else null
    }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        // Mode celebration : on remplace tout le contenu par les emoji de
        // felicitations. Le confetti est gere au niveau du Box parent.
        if (celebration != null) {
            CelebrationCardContent(emoji = celebration.first, text = celebration.second)
            return@Card
        }
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge raison + bouton edit
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = reasonIcon,
                    contentDescription = null,
                    tint = if (suggestion.reason == "urgent") TaskUrgencyCalculator.ROUGE else TaskUrgencyCalculator.VERT,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (suggestion.reason == "urgent") TaskUrgencyCalculator.ROUGE else TaskUrgencyCalculator.VERT,
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

            // Countdown text (dans Nj / aujourd'hui / en retard de Nj). Couleur
            // selon l'etat (vert/orange/rouge) renvoye par /api/suggestions.
            val countdownLabel = TaskUrgencyCalculator.countdownLabelFromDays(suggestion.daysUntilDue)
            if (countdownLabel.isNotEmpty()) {
                Text(
                    text = countdownLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskUrgencyCalculator.stateColor(suggestion.state),
                    fontWeight = FontWeight.Medium,
                )
            }

            // Piece + categorie (ligne discrete sous le titre)
            TaskMetaRow(room = room, category = category)

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
                    colors = ButtonDefaults.buttonColors(containerColor = TaskUrgencyCalculator.VERT)
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
    room: Room?,
    category: Category?,
    isCelebrating: Boolean,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val celebration = remember(tip.taskId, isCelebrating) {
        if (isCelebrating) randomCelebration() else null
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        if (celebration != null) {
            CelebrationCardContent(emoji = celebration.first, text = celebration.second)
            return@Card
        }
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

            // Piece + categorie sous le titre
            TaskMetaRow(room = room, category = category)

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
                    colors = ButtonDefaults.buttonColors(containerColor = TaskUrgencyCalculator.VERT)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fait !")
                }
            }
        }
    }
}

/**
 * Ligne de meta sous un titre de tache : emoji+nom de la piece + nom de
 * la categorie en pastille discrete. Affiche rien si les deux sont null.
 *
 * Pattern utilise par SuggestionCard et InProgressCard pour rappeler le
 * contexte (ou est la tache, dans quel "univers" elle vit).
 */
@Composable
private fun TaskMetaRow(room: Room?, category: Category?) {
    if (room == null && category == null) return
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (room != null) {
            val label = if (!room.emoji.isNullOrBlank()) "${room.emoji} ${room.name}" else room.name
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (category != null) {
            val catColor = remember(category.color) {
                try { Color(android.graphics.Color.parseColor(category.color)) }
                catch (_: Exception) { Color.Gray }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(catColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = catColor,
                )
            }
        }
    }
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

package com.torchons.app.ui.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Suggestion
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.SoloPointsInfoDialog
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.ui.common.randomCelebration
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.TaskUrgencyCalculator

@Composable
fun HomeScreen(
    onTaskClick: (String) -> Unit = {},
    onEditTask: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    /** P1 refonte — tap sur la card "Foyer" : navigue vers l'ecran Foyer
     *  (design decision 7). La card "Moi" garde le dialog de simulation. */
    onFoyerClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val userId by viewModel.userId.collectAsState()
    val affinityEnabled by viewModel.affinityPopupEnabled.collectAsState()
    val rooms by viewModel.rooms.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val lastCompletionsByTask by viewModel.lastCompletionsByTask.collectAsState()
    val gamificationVisible by viewModel.gamificationVisible.collectAsState()
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

    // v0.16+ : Snackbar du recycle dismiss (tier-aware). Le SharedFlow
    // n'emet qu'une fois par recycle, pas de replay sur recomposition.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.recycleToast.collect { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short,
            )
        }
    }
    // E2.1 — refresh à chaque (ré)ouverture de l'onglet Accueil : couvre
    // les projections (pace/retard/maintenance) + prompts serveur-calculés
    // stales après une mutation faite ailleurs (modal présence, édit tâche
    // depuis Tasks, etc.). Cache-conventions règle 1.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Recharger au retour sur cet ecran + checkin presence WiFi (v2 home
    // freeze — signal positif uniquement, pas de report negatif).
    // Refresh aussi les propositions actives en poll de fallback.
    LifecycleResumeEffect(Unit) {
        viewModel.loadData()
        viewModel.reportPresenceNow()
        viewModel.refreshPendingProposition()
        onPauseOrDispose { }
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
    // P4b.2 — modal unique "Presence & series" pour resoudre les prompts
    // RESOLVE_PRESENCE (cf bloc PromptModal + bloc d'ouverture plus bas).
    // Le flag `fromPrompt` indique qu'on doit resoudre le prompt en ANSWERED
    // quand l'user ferme le modal (les jours ont ete persistes via PUT
    // direct lors des taps).
    var streakCalendarUserId by remember { mutableStateOf<String?>(null) }
    var streakCalendarFromPrompt by remember { mutableStateOf(false) }
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

    // Queue unifiee des prompts (BUMP_EFFORT / VOTE_RATING / RESOLVE_PRESENCE
    // / VOTE_FREQUENCY / VOTE_EFFORT). RESOLVE_PRESENCE ouvre le
    // StreakCalendarModal (one modal to rule them all) : meme surface
    // d'edition que l'ecran Foyer / Stats. Le prompt est resolu en
    // ANSWERED quand l'user ferme le modal ; les jours edites sont deja
    // persistes via PUT /presence direct (taps dans le modal). Cf state
    // `streakCalendarUserId` + `streakCalendarFromPrompt` declares plus
    // haut, et le bloc d'ouverture du modal apres ce PromptModal.
    if (uiState.showPromptModal && uiState.currentPrompt != null) {
        com.torchons.app.ui.common.PromptModal(
            prompt = uiState.currentPrompt!!,
            onDismiss = { viewModel.dismissCurrentPrompt() },
            onBumpEffort = {
                viewModel.resolveCurrentPrompt("ANSWERED", mapOf("action" to "bump_effort"))
            },
            onOpenRating = { taskId ->
                viewModel.resolveCurrentPrompt("ANSWERED")
            },
            onOpenPresenceCalendar = {
                // Ouvre StreakCalendarModal pre-positionne sur l'user
                // courant. Le prompt reste 'active' en backend tant que le
                // modal n'est pas ferme — on resout au close ci-dessous.
                streakCalendarFromPrompt = true
                streakCalendarUserId = userId
            },
            onOpenFrequencyVote = { taskId, requester ->
                viewModel.openFrequencyVoteFromPrompt(taskId, requester)
            },
            onOpenEffortVote = { taskId, requester ->
                viewModel.openEffortVoteFromPrompt(taskId, requester)
            },
        )
    }

    // P4b.2 — StreakCalendarModal partage entre :
    //  (a) prompt RESOLVE_PRESENCE (open via PromptModal callback ci-dessus,
    //      streakCalendarFromPrompt = true)
    //  (b) navigation manuelle future (carte stats Resume / profil — pas
    //      branche depuis HomeScreen actuellement mais l'etat est pret).
    // Au close : si fromPrompt -> resolve ANSWERED (les jours sont deja
    // persistes via PUT /presence direct dans le modal). Sinon : juste
    // fermer.
    streakCalendarUserId?.let { uid ->
        val members = remember(uiState.fairShare) {
            uiState.fairShare
                .map { com.torchons.app.ui.stats.StreakMember(it.userId, it.displayName) }
                .distinctBy { it.userId }
        }
        com.torchons.app.ui.stats.StreakCalendarModal(
            members = members,
            initialUserId = uid,
            onDismiss = {
                val wasFromPrompt = streakCalendarFromPrompt
                streakCalendarUserId = null
                streakCalendarFromPrompt = false
                if (wasFromPrompt) {
                    // Resout le prompt RESOLVE_PRESENCE en ANSWERED sans
                    // payload : les jours sont deja persistes via PUT
                    // /presence (taps dans le modal). Le backend marque
                    // le prompt comme repondu, le bandeau "jours non
                    // confirmes" disparait.
                    viewModel.resolveCurrentPrompt("ANSWERED")
                } else {
                    // Navigation manuelle : juste refresh la home pour que
                    // les projections / urgences reflètent la nouvelle
                    // presence (cache-conventions regle 1).
                    viewModel.refresh()
                }
            },
        )
    }

    // v0.28 — Dialogs declenches par les prompts VOTE_FREQUENCY/VOTE_EFFORT.
    uiState.pendingFrequencyVoteContext?.let { ctx ->
        ctx.frequencyState?.let { state ->
            com.torchons.app.ui.common.FrequencyVoteDialog(
                taskTitle = ctx.taskTitle,
                state = state,
                requesterName = ctx.requesterName,
                onSubmit = { request -> viewModel.submitPromptFrequencyVote(request) },
                onDismiss = { viewModel.dismissPromptFrequencyVote() },
            )
        }
    }
    uiState.pendingEffortVoteContext?.let { ctx ->
        ctx.effortState?.let { state ->
            com.torchons.app.ui.common.EffortVoteDialog(
                taskTitle = ctx.taskTitle,
                state = state,
                requesterName = ctx.requesterName,
                onSubmit = { points -> viewModel.submitPromptEffortVote(points) },
                onDismiss = { viewModel.dismissPromptEffortVote() },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bandeau "jours non confirmes" (home freeze v2)
        if (uiState.hasUnresolvedPresenceDays && uiState.unresolvedPresenceDaysCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3CD),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            pluralStringResource(
                                R.plurals.home_unresolved_presence_days,
                                uiState.unresolvedPresenceDaysCount,
                                uiState.unresolvedPresenceDaysCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E),
                        )
                        Text(
                            stringResource(R.string.home_unresolved_presence_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB45309),
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.reopenPresencePrompt() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF92400E)),
                    ) {
                        Text(stringResource(R.string.home_unresolved_presence_resolve), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Salutation + bouton noter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = displayName?.let { stringResource(R.string.home_greeting_format, it) }
                    ?: stringResource(R.string.home_greeting_fallback),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Sync status icon (replaces the old full-width OfflineBanner
            // that used to push the whole layout down when appearing).
            com.torchons.app.ui.components.SyncStatusIcon()
            IconButton(onClick = { viewModel.showManualRating() }) {
                Icon(
                    imageVector = Icons.Default.QuestionMark,
                    contentDescription = stringResource(R.string.home_rate_task_cd),
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
            onMoiClick = { showProjectionDialog = true },
            onFoyerClick = onFoyerClick,
        )

        // Suggestions (titre retire v0.8.0 — evident que c'est des suggestions)
        // Filtrage + tampon : on filtre les suggestions recemment completees
        // cote client (justCompletedIds) pour eviter de re-afficher la meme
        // carte pendant le gap "sync en vol". On garde visible la carte en
        // cours de celebration (pour l'animation) meme si deja dans le set.
        // Le backend envoie maintenant jusqu'a 5+5 suggestions (v0.11.1) ce
        // qui garantit qu'il y a quasi toujours du stock a afficher.
        // v0.16+ : on filtre aussi dismissedIds (clic ✕ sur la carte), reset
        // au cold start — preserve a travers LifecycleResumeEffect.
        val visibleSuggestions = uiState.suggestions.filter { s ->
            s.taskId == uiState.celebratingTaskId ||
                (s.taskId !in uiState.justCompletedIds && s.taskId !in uiState.dismissedIds)
        }
        // Top 1 par tier : urgent + affinity + uncertain (3 cards max).
        // La 3e section "Sans cadence" (v0.14, Q3) ne s'affiche que si le
        // backend a retourne au moins une suggestion reason=uncertain.
        val displayUrgent = visibleSuggestions.firstOrNull { it.reason == "urgent" }
        val displayAffinity = visibleSuggestions.firstOrNull { it.reason == "affinity" }
        val displayUncertain = visibleSuggestions.firstOrNull { it.reason == "uncertain" }
        val displayedSuggestions = listOfNotNull(displayUrgent, displayAffinity, displayUncertain)

        if (displayedSuggestions.isNotEmpty()) {
            // Household members for the "Together" participant selector.
            val members = remember(uiState.fairShare) {
                uiState.fairShare.map { it.userId to it.displayName }.distinctBy { it.first }
            }
            displayedSuggestions.forEach { suggestion ->
                val task = tasksById[suggestion.taskId]
                val room = task?.roomId?.let { roomsById[it] }
                val category = task?.categoryId?.let { categoriesById[it] }
                SuggestionCard(
                    suggestion = suggestion,
                    room = room,
                    category = category,
                    lastCompletedAt = lastCompletionsByTask[suggestion.taskId],
                    isCelebrating = uiState.celebratingTaskId == suggestion.taskId,
                    celebrationPoints = if (uiState.celebratingTaskId == suggestion.taskId && gamificationVisible) uiState.celebrationPoints else null,
                    celebrationReason = if (uiState.celebratingTaskId == suggestion.taskId) uiState.celebrationReason else null,
                    gamificationVisible = gamificationVisible,
                    celebrationEarnedBy = if (uiState.celebratingTaskId == suggestion.taskId && gamificationVisible) uiState.celebrationEarnedBy else null,
                    members = members,
                    currentUserId = userId.orEmpty(),
                    previewProvider = { iso, n ->
                        viewModel.fetchCompletionPreview(suggestion.taskId, iso, n)
                    },
                    onClick = { onTaskClick(suggestion.taskId) },
                    onCompleteToday = { pts, reason ->
                        viewModel.completeTask(suggestion.taskId, pts, reason)
                    },
                    onCompleteAtDate = { iso, by, pts, reason ->
                        viewModel.completeTaskAtDate(
                            suggestion.taskId, iso,
                            completedBy = by,
                            previewPoints = pts,
                            previewReason = reason,
                        )
                    },
                    onCompleteCollective = { ids, iso ->
                        viewModel.completeTaskCollective(suggestion.taskId, ids, iso)
                    },
                    onEdit = { onEditTask(suggestion.taskId) },
                    onDismiss = { viewModel.dismissSuggestion(suggestion.taskId, suggestion.reason) },
                )
            }
        }

        // Loading placeholder : uniquement si le tampon filtre est vide MAIS
        // qu'on a recemment complete quelque chose (donc le serveur va bientot
        // nous renvoyer de nouvelles suggestions). Evite le flash "Tout est
        // frais" entre 2 refresh pendant un burst de completions.
        if (displayedSuggestions.isEmpty() && uiState.justCompletedIds.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.home_loading_next_suggestion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (displayedSuggestions.isEmpty() && uiState.justCompletedIds.isEmpty()) {
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
                            text = stringResource(R.string.home_backend_unreachable_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.home_backend_unreachable_message),
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
                            text = stringResource(R.string.home_all_fresh_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.home_all_fresh_message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Mini-courbe Retard du foyer sur 30 jours glissants — placee tout
        // en bas (apres les taches urgentes) car c'est une lecture
        // contextuelle, pas une action a entreprendre. Carte cachee si la
        // timeline est vide (foyer fraichement cree, aucun snapshot).
        uiState.retardTimelineMonth?.takeIf { it.points.size >= 2 }?.let { timeline ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_retard_chart_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    com.torchons.app.ui.stats.RetardTimelineChart(data = timeline)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Version
        Text(
            text = stringResource(R.string.home_version_footer_format, com.torchons.app.BuildConfig.VERSION_NAME),
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

    // v0.16+ : SnackbarHost pour messages de recycle dismiss (tier-aware).
    // Aligne en bas de l'ecran, au-dessus de la BottomNav si presente.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    } // fin du Box outer (celebration overlay layer)
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    room: Room?,
    category: Category?,
    lastCompletedAt: String? = null,
    isCelebrating: Boolean,
    celebrationPoints: Int? = null,
    celebrationReason: String? = null,
    /** v0.32 : si false, masque la pastille bonus, la ligne "💰 N pts" et
     *  le "+X pts" dans la celebration. Le reste (titre, countdown, piece,
     *  bouton "Fait !") reste inchange. */
    gamificationVisible: Boolean = true,
    /** Per-participant attribution shown in the celebration (name, points). */
    celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /** Household members as (userId, displayName) pairs, deduplicated. */
    members: List<Pair<String, String>> = emptyList(),
    currentUserId: String = "",
    /** Server points preview for (isoDate, numParticipants); null offline. */
    previewProvider: suspend (isoDate: String, numParticipants: Int) -> com.torchons.app.data.model.CompletionPreview? = { _, _ -> null },
    onClick: () -> Unit,
    /** Immediate completion by the current user, dated now (J-0 path). */
    onCompleteToday: (previewPoints: Int?, previewReason: String?) -> Unit,
    /** Backdated single completion; completedBy null = current user. */
    onCompleteAtDate: (isoDate: String, completedBy: String?, previewPoints: Int?, previewReason: String?) -> Unit,
    /** Collective completion by the given participants at the given date. */
    onCompleteCollective: (participantIds: List<String>, isoDate: String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val reasonLabel = when (suggestion.reason) {
        "urgent" -> stringResource(R.string.suggestion_reason_urgent)
        "uncertain" -> stringResource(R.string.suggestion_reason_uncertain)
        else -> stringResource(R.string.suggestion_reason_affinity)
    }
    val reasonIcon = when (suggestion.reason) {
        "urgent" -> Icons.Default.Warning
        "uncertain" -> Icons.Default.Refresh
        else -> Icons.Default.Favorite
    }
    val reasonColor = when (suggestion.reason) {
        "urgent" -> TaskUrgencyCalculator.ROUGE
        else -> TaskUrgencyCalculator.VERT
    }
    // Pickee une seule fois pour cette celebration (key suggestion.taskId).
    val celebration = remember(suggestion.taskId, isCelebrating) {
        if (isCelebrating) randomCelebration() else null
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
            CelebrationCardContent(
                emoji = celebration.first,
                text = celebration.second,
                points = celebrationPoints,
                reason = celebrationReason,
                earnedBy = celebrationEarnedBy,
            )
            return@Card
        }
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge raison + pastille bonus + bouton edit
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = reasonIcon,
                    contentDescription = null,
                    tint = reasonColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = reasonColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (gamificationVisible && suggestion.bonusEligible && suggestion.bonusPct > 0f) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFFE8D6))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.suggestion_bonus_badge_format, (suggestion.bonusPct * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC2410C),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.suggestion_edit_cd),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // v0.16+ : ignorer cette suggestion (session only, reset au
                // cold start). La suivante du meme tier prend la place.
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.suggestion_dismiss_cd),
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
            // Pour uncertain (v0.14) : libelle factuel "Fait il y a X j" au lieu
            // du countdown (pas de notion de retard, Q2/Q9).
            val urgencyRes = androidx.compose.ui.platform.LocalContext.current.resources
            val countdownLabel = if (suggestion.reason == "uncertain") {
                TaskUrgencyCalculator.uncertainCountdownLabel(
                    res = urgencyRes,
                    lastCompletedAt = lastCompletedAt,
                    today = java.time.LocalDate.now(),
                )
            } else {
                TaskUrgencyCalculator.countdownLabelFromDays(urgencyRes, suggestion.daysUntilDue)
            }
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

            // Completion controls state — declared before the points line
            // because the predicted points follow the selected date. One
            // selectedDate per card, shared by both buttons; keyed on taskId
            // so it resets to "today / everyone" whenever the suggested task
            // changes or the screen leaves composition.
            val today = java.time.LocalDate.now()
            var selectedDate by remember(suggestion.taskId) { mutableStateOf(today) }
            // Defensive clamp if the app crossed midnight while in memory.
            if (selectedDate.isAfter(today)) selectedDate = today
            val memberIds = remember(members) { members.map { it.first } }
            var selectedParticipantIds by remember(suggestion.taskId, memberIds) {
                mutableStateOf(memberIds.toSet())
            }
            var showDatePicker by remember(suggestion.taskId) { mutableStateOf(false) }
            var showParticipantsMenu by remember(suggestion.taskId) { mutableStateOf(false) }

            val dayOffset = java.time.temporal.ChronoUnit.DAYS
                .between(selectedDate, today).toInt()
            val dayLabel = CompletionDateLabels.relativeDayLabel(urgencyRes, selectedDate, today)

            // Date-aware points preview: backdating changes bonus/malus, so
            // the points line must follow the selected date (a late bonus
            // disappears when the completion is dated back before the due
            // date). J-0 keeps the /suggestions prediction; null on fetch
            // failure (offline) falls back to it too.
            var datePreview by remember(suggestion.taskId) {
                mutableStateOf<com.torchons.app.data.model.CompletionPreview?>(null)
            }
            LaunchedEffect(suggestion.taskId, selectedDate) {
                datePreview = if (dayOffset == 0) null
                else previewProvider(CompletionDateLabels.toIsoNoonUtc(selectedDate), 1)
            }
            // Best-known single-completion points for the current selection,
            // passed to the celebration so it can attribute exact points.
            val singlePreviewPoints = datePreview?.finalPoints
                ?: suggestion.predictedFinalPoints.takeIf { dayOffset == 0 }
            val singlePreviewReason = datePreview?.reason
                ?: "BONUS_RETARD".takeIf {
                    dayOffset == 0 && suggestion.bonusEligible &&
                        suggestion.predictedFinalPoints > suggestion.predictedBasePoints
                }

            // Ligne points predits : "💰 N pts" (+ delta bonus si applicable).
            // Pour les taches solo (predicted = 0) : "💰 0 pt" + icone (i)
            // cliquable qui ouvre SoloPointsInfoDialog (pedago "pourquoi 0 ?").
            // Cachee en mode opt-out (v0.32).
            if (gamificationVisible) {
                SuggestionPointsLine(suggestion = suggestion, preview = datePreview)
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Left slot: -1 day arrow, becomes a calendar shortcut past 15
            // days back. Right slot: +1 day arrow, calendar shortcut at J-0
            // (there is never a future date to navigate to).
            val atCalendarDepth = dayOffset >= MAX_ARROW_NAV_DAYS
            val leftIcon =
                if (atCalendarDepth) Icons.Default.CalendarMonth
                else Icons.AutoMirrored.Filled.KeyboardArrowLeft
            val leftCd = stringResource(
                if (atCalendarDepth) R.string.home_date_open_calendar_cd
                else R.string.home_date_prev_day_cd
            )
            val onLeftSlot: () -> Unit = {
                if (dayOffset >= MAX_ARROW_NAV_DAYS) showDatePicker = true
                else selectedDate = selectedDate.minusDays(1)
            }
            val rightIcon =
                if (dayOffset == 0) Icons.Default.CalendarMonth
                else Icons.AutoMirrored.Filled.KeyboardArrowRight
            val rightCd = stringResource(
                if (dayOffset == 0) R.string.home_date_open_calendar_cd
                else R.string.home_date_next_day_cd
            )
            val onRightSlot: () -> Unit = {
                if (dayOffset == 0) showDatePicker = true
                else selectedDate = selectedDate.plusDays(1)
            }

            // Button 1 — "Done <day>": immediate completion by me.
            SegmentedCompletionButton(
                centerLabel = stringResource(R.string.home_done_day_format, dayLabel),
                leftIcon = leftIcon,
                leftContentDescription = leftCd,
                onLeftClick = onLeftSlot,
                rightIcon = rightIcon,
                rightContentDescription = rightCd,
                onRightClick = onRightSlot,
                onCenterClick = {
                    if (dayOffset == 0) {
                        onCompleteToday(singlePreviewPoints, singlePreviewReason)
                    } else {
                        onCompleteAtDate(
                            CompletionDateLabels.toIsoNoonUtc(selectedDate),
                            null,
                            singlePreviewPoints,
                            singlePreviewReason,
                        )
                    }
                },
                filled = true,
            )

            // Button 2 — "Together <day>" with participant chevron. Hidden
            // for single-member households (no one to share with).
            if (members.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))

                val allSelected = selectedParticipantIds.size == memberIds.size
                val centerLabel = when {
                    allSelected -> stringResource(R.string.home_together_day_format, dayLabel)
                    selectedParticipantIds.size == 1 -> stringResource(
                        R.string.home_done_by_one_day_format,
                        members.firstOrNull { it.first == selectedParticipantIds.first() }?.second.orEmpty(),
                        dayLabel,
                    )
                    else -> pluralStringResource(
                        R.plurals.home_done_by_people_day,
                        selectedParticipantIds.size,
                        selectedParticipantIds.size,
                        dayLabel,
                    )
                }
                SegmentedCompletionButton(
                    centerLabel = centerLabel,
                    leftIcon = leftIcon,
                    leftContentDescription = leftCd,
                    onLeftClick = onLeftSlot,
                    rightIcon = rightIcon,
                    rightContentDescription = rightCd,
                    onRightClick = onRightSlot,
                    onCenterClick = {
                        val iso = CompletionDateLabels.toIsoNoonUtc(selectedDate)
                        if (selectedParticipantIds.size == 1) {
                            onCompleteAtDate(
                                iso,
                                selectedParticipantIds.first(),
                                singlePreviewPoints,
                                singlePreviewReason,
                            )
                        } else {
                            // Collective: the server response corrects the
                            // celebration with the exact per-participant split.
                            onCompleteCollective(selectedParticipantIds.toList(), iso)
                        }
                    },
                    filled = false,
                    chevronContentDescription = stringResource(R.string.home_participants_chevron_cd),
                    onChevronClick = { showParticipantsMenu = true },
                    chevronMenu = {
                        ParticipantsMenu(
                            expanded = showParticipantsMenu,
                            members = members,
                            currentUserId = currentUserId,
                            selectedIds = selectedParticipantIds,
                            onSelectionChange = { selectedParticipantIds = it },
                            onDismiss = { showParticipantsMenu = false },
                        )
                    },
                )
            }

            if (showDatePicker) {
                PastOnlyDatePickerDialog(
                    initialDate = selectedDate,
                    onDateSelected = {
                        selectedDate = it.coerceAtMost(today)
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false },
                )
            }
        }
    }
}

/** Arrow navigation depth: past this many days back, the left slot turns
 *  into a calendar shortcut (the picker becomes the only way further). */
private const val MAX_ARROW_NAV_DAYS = 15

/**
 * Participant selector anchored to the chevron slot of the "Together"
 * button. Two-member household: radio choice between "Together" (both)
 * and "Just <other>". Three or more: one checkbox row per member (at
 * least one must stay selected), menu stays open while toggling.
 */
@Composable
private fun ParticipantsMenu(
    expanded: Boolean,
    members: List<Pair<String, String>>,
    currentUserId: String,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val memberIds = members.map { it.first }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (members.size == 2) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_participants_together)) },
                leadingIcon = {
                    RadioButton(selected = selectedIds.size == memberIds.size, onClick = null)
                },
                onClick = {
                    onSelectionChange(memberIds.toSet())
                    onDismiss()
                },
            )
            val other = members.firstOrNull { it.first != currentUserId }
            if (other != null) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.home_participants_just_other_format, other.second))
                    },
                    leadingIcon = {
                        RadioButton(selected = selectedIds == setOf(other.first), onClick = null)
                    },
                    onClick = {
                        onSelectionChange(setOf(other.first))
                        onDismiss()
                    },
                )
            }
        } else {
            members.forEach { (id, name) ->
                val checked = id in selectedIds
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = {
                        val next = when {
                            !checked -> selectedIds + id
                            selectedIds.size > 1 -> selectedIds - id
                            else -> selectedIds // never allow an empty selection
                        }
                        onSelectionChange(next)
                    },
                )
            }
        }
    }
}

/**
 * Material3 DatePickerDialog restricted to past dates (today included).
 * Only sets the shared selected date — completion stays on the center tap.
 * Millis are interpreted as UTC calendar days, consistent with
 * [CompletionDateLabels.millisToIsoNoonUtc].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastOnlyDatePickerDialog(
    initialDate: java.time.LocalDate,
    onDateSelected: (java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Bound by the user's local "today": no future day can be picked.
    val maxDate = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = java.time.Instant.ofEpochMilli(utcTimeMillis)
                    .atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                return !date.isAfter(maxDate)
            }

            override fun isSelectableYear(year: Int): Boolean = year <= maxDate.year
        },
    )

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    onDateSelected(
                        java.time.Instant.ofEpochMilli(millis)
                            .atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                    )
                }
            }) {
                Text(stringResource(R.string.common_validate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

/**
 * Ligne "💰 N pts" sous la card home, juste au-dessus du bouton "Fait !".
 *
 * Affiche les points predits par /api/suggestions (predicted_final_points)
 * pour rendre la gamification lisible AVANT la completion (l'utilisateur
 * sait combien il va gagner avant de cliquer).
 *
 * Trois cas visuels :
 *  - Solo (predicted = 0) : "💰 0 pt" en gris + icone (i) qui ouvre
 *    SoloPointsInfoDialog (pedago "pourquoi 0 ?").
 *  - Bonus retard eligible (bonusEligible) : "💰 N pts" en vert avec un
 *    suffixe "+M pts bonus retard" pour rendre le bonus tangible.
 *  - Cas standard : "💰 N pts" neutre.
 */
@Composable
private fun SuggestionPointsLine(
    suggestion: Suggestion,
    /** Date-aware server preview; overrides the /suggestions prediction
     *  when the user navigated to a past day (late bonus may disappear,
     *  an early malus may appear). Null = J-0 or offline fallback. */
    preview: com.torchons.app.data.model.CompletionPreview? = null,
) {
    var showInfo by remember { mutableStateOf(false) }
    val finalPts = preview?.finalPoints ?: suggestion.predictedFinalPoints
    val basePts = preview?.basePoints ?: suggestion.predictedBasePoints
    val delta = finalPts - basePts
    // Solo detection stays on the suggestion values: a solo task predicts
    // 0 whatever the date, while a preview could legitimately reach 0.
    val isSolo = suggestion.predictedFinalPoints == 0 && suggestion.predictedBasePoints == 0
    val bonusShown = delta > 0 && (preview?.reason == "BONUS_RETARD" || (preview == null && suggestion.bonusEligible))
    val malusShown = delta < 0
    val color = when {
        isSolo -> MaterialTheme.colorScheme.onSurfaceVariant
        bonusShown -> Color(0xFF2E7D32)
        malusShown -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "💰 ",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = pluralStringResource(R.plurals.suggestion_predicted_points, finalPts, finalPts),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (!isSolo && bonusShown) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.suggestion_bonus_delta_points, delta, delta),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (!isSolo && malusShown) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.suggestion_malus_delta_points, -delta, -delta),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (isSolo) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.suggestion_solo_points_info_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { showInfo = true },
            )
        }
    }

    if (showInfo) {
        SoloPointsInfoDialog(onDismiss = { showInfo = false })
    }
}

/**
 * Ligne de meta sous un titre de tache : emoji+nom de la piece + nom de
 * la categorie en pastille discrete. Affiche rien si les deux sont null.
 *
 * Pattern utilise par SuggestionCard pour rappeler le contexte (ou est
 * la tache, dans quel "univers" elle vit).
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


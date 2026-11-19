package com.torchons.app.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import com.torchons.app.ui.common.ImeSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.showUserError

@Composable
fun HomeScreen(
    onTaskClick: (String) -> Unit = {},
    onEditTask: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    /** P1 refonte — tap sur la card "Foyer" : navigue vers l'ecran Foyer
     *  (design decision 7). La card "Moi" garde le dialog de simulation. */
    onFoyerClick: () -> Unit = {},
    /** Button of the empty-household invitation: opens the Tasks tab. */
    onAddTasks: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasNoTasks by viewModel.hasNoTasks.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val userId by viewModel.userId.collectAsState()
    val affinityEnabled by viewModel.affinityPopupEnabled.collectAsState()

    val activeTasks by viewModel.activeTasks.collectAsState()
    val lastCompletionsByTask by viewModel.lastCompletionsByTask.collectAsState()
    val gamificationVisible by viewModel.gamificationVisible.collectAsState()
    val toastContext = LocalContext.current

    // Les contextes voyagent AVEC la tache : plus besoin de maps de lookup,
    // c'est autant d'etat derive en moins a garder synchrone.
    val tasksById = remember(activeTasks) { activeTasks.associateBy { it.id } }

    // v0.16+ : Snackbar du recycle dismiss (tier-aware). Le SharedFlow
    // n'emet qu'une fois par recycle, pas de replay sur recomposition.
    val snackbarHostState = remember { SnackbarHostState() }
    // A reportable error needs its "Send to Quentin" button: snackbar (cleared
    // once it goes away, clearing first would cancel it). Others stay a toast.
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            if (it.reportable) {
                snackbarHostState.showUserError(toastContext, it, "Home")
            } else {
                Toast.makeText(toastContext, it.message, Toast.LENGTH_SHORT).show()
            }
            viewModel.clearError()
        }
    }
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
        // Le contexte PRINCIPAL de la tache tient lieu de lieu affiche.
        val pendingTask = tasksById[pending.taskId]
        val pendingRoomLabel = pendingTask?.contexts?.firstOrNull()?.label
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
            if (it.reportable) {
                snackbarHostState.showUserError(toastContext, it, "Home")
            } else {
                Toast.makeText(toastContext, it.message, Toast.LENGTH_SHORT).show()
            }
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

    // Queue unifiee des prompts (BUMP_EFFORT / VOTE_* / RESOLVE_PRESENCE /
    // DISCOVER_MODULE). RESOLVE_PRESENCE ouvre le StreakCalendarModal, resolu
    // en ANSWERED a la fermeture (cf `streakCalendarFromPrompt` plus haut).
    // Guide de module a l'ouverture : jamais empile sur un prompt.
    com.torchons.app.ui.common.ModuleGuideHost(promptShowing = uiState.showPromptModal || uiState.currentPrompt != null)
    if (uiState.showPromptModal && uiState.currentPrompt != null) {
        com.torchons.app.ui.common.PromptModal(
            prompt = uiState.currentPrompt!!,
            onDismiss = { viewModel.dismissCurrentPrompt() },
            // « Souvent en retard »: later = asked again at the next late completion,
            // ignore = DISMISSED with the grace period (backend api/prompts.py).
            onBumpRevote = { viewModel.startBumpRevote() },
            onBumpLater = { viewModel.resolveCurrentPrompt("DISMISSED", mapOf("action" to "later")) },
            onBumpIgnore = { viewModel.resolveCurrentPrompt("DISMISSED", mapOf("action" to "ignore")) },
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
            // P4 — accepter suffit : c'est le backend qui active le module, sur
            // le meme appel que la resolution. Deux allers-retours laisseraient
            // une fenetre ou le prompt est resolu et le module toujours eteint.
            onAcceptModule = {
                viewModel.resolveCurrentPrompt("ANSWERED", mapOf("accepted" to true))
            },
            onDeclineModule = {
                // `declined` marque un refus VU par quelqu'un. Sans lui, le
                // serveur ne le compte pas dans le budget — c'est ce qui
                // protege les clients qui ne connaissent pas encore ce type
                // de prompt et le resolvent sans rien afficher.
                viewModel.resolveCurrentPrompt("DISMISSED", mapOf("declined" to true))
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
                header = ctx.header,
                state = state,
                requesterName = ctx.requesterName,
                onSubmit = { request -> viewModel.submitPromptFrequencyVote(request) },
                onDismiss = { viewModel.dismissPromptFrequencyVote() },
                introText = ctx.bump?.let {
                    stringResource(R.string.bump_revote_intro, it.latePercent, it.windowDays, 1)
                },
            )
        }
    }
    uiState.pendingEffortVoteContext?.let { ctx ->
        ctx.effortState?.let { state ->
            com.torchons.app.ui.common.EffortVoteDialog(
                header = ctx.header,
                state = state,
                requesterName = ctx.requesterName,
                onSubmit = { points -> viewModel.submitPromptEffortVote(points) },
                onDismiss = { viewModel.dismissPromptEffortVote() },
                introText = ctx.bump?.let {
                    stringResource(R.string.bump_revote_intro, it.latePercent, it.windowDays, 2)
                },
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

        // Greeting + rate button + "feature turned off by a member" notice
        HomeGreeting(
            displayName = displayName,
            onRate = { viewModel.showManualRating() },
            onSeeFoyer = onFoyerClick,
        )

        // « À faire aujourd'hui »: the red stack, one card at a time, on top (HomeTodayStack.kt).
        val todayCard by viewModel.todayStack.current.collectAsState()
        val todayExcluded by viewModel.todayStack.excluded.collectAsState()
        val memberNames by viewModel.memberNames.collectAsState()
        todayCard?.let { card ->
            HomeTodayCard(
                viewModel, card, memberNames.toList(), memberNames, gamificationVisible, onTaskClick, onEditTask,
            )
        }

        // 2 cards projection (v0.8.0 magic buttons)
        // P3 — retard, rythme et objectif appartiennent au module `objectives`.
        if (moduleEnabled(Modules.OBJECTIVES)) {
        ProjectionCardsRow(
            pace = uiState.pace,
            retard = uiState.retard,
            maintenance = uiState.maintenance,
            currentUserId = userId,
            onMoiClick = { showProjectionDialog = true },
            onFoyerClick = onFoyerClick,
        )
        }

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
                (s.taskId !in uiState.justCompletedIds && s.taskId !in uiState.dismissedIds &&
                    // Handed by me or to me: in no suggestion pile (also passed to /suggestions).
                    s.taskId !in todayExcluded)
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
                // Principal = le lieu, secondaire = la nature. Deux contextes
                // suffisent a l'affichage : au-dela, la carte devient illisible.
                val room = task?.contexts?.getOrNull(0)
                val category = task?.contexts?.getOrNull(1)
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
                    members = if (task?.assignedToIds?.size == 1) emptyList() else members, // solo: nobody else
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
            HomeEmptyState(
                backendUnreachable = uiState.lastRefreshFailed && !uiState.hasCachedData,
                householdEmpty = hasNoTasks == true && uiState.hasCachedData,
                onAddTasks = onAddTasks,
            )
        }

        // Mini-courbe Retard du foyer sur 30 jours glissants — placee tout
        // en bas (apres les taches urgentes) car c'est une lecture
        // contextuelle, pas une action a entreprendre. Carte cachee si la
        // timeline est vide (foyer fraichement cree, aucun snapshot).
        // Tracking the household backlog belongs to `objectives`: hidden with it.
        val retardCurveOn = moduleEnabled(Modules.OBJECTIVES)
        uiState.retardTimelineMonth?.takeIf { retardCurveOn && it.points.size >= 2 }?.let { timeline ->
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
    ImeSnackbarHost(
        state = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    } // fin du Box outer (celebration overlay layer)
}


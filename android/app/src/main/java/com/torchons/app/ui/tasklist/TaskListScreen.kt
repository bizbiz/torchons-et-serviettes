package com.torchons.app.ui.tasklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.CompletionDialog
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.today.TodayActionDialogs
import com.torchons.app.ui.today.rememberTodayActionState
import com.torchons.app.ui.today.todayCardState
import com.torchons.app.ui.common.InfoTooltip

/**
 * Ecran principal affichant la liste des taches.
 * Fonctionnalites :
 * - Filtrage par categorie via chips
 * - Taches triees par urgence (fraicheur)
 * - Swipe pour completer une tache
 * - Pull-to-refresh
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onTaskClick: (String) -> Unit,
    onCreateTask: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToFoyerSettings: () -> Unit = {},
    viewModel: TaskListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val users by viewModel.allUsers.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    val gamificationVisible by viewModel.gamificationVisible.collectAsState()

    // E2.1 — refresh à chaque (ré)ouverture de l'onglet Tâches : Room
    // (observeCache) capte déjà les mutations locales, mais l'urgence
    // serveur-calculée peut être stale après une mutation faite ailleurs
    // (ex. édit présence dans modal). Cache-conventions règle 1.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // « Date + Qui » (ui/common/CompletionDialog), shared by every card and by the widget.
    var dialogState by remember { mutableStateOf<TaskListCompletionDialogState?>(null) }

    dialogState?.let { ds ->
        CompletionDialog(
            taskTitle = ds.taskTitle,
            allMembers = users.map { it.id to it.displayName },
            assignedMemberIds = ds.assignedIds,
            currentUserId = currentUserId.orEmpty(),
            onDismiss = { dialogState = null },
            onConfirm = { participantIds, isoDate, preview ->
                if (participantIds.size == 1) {
                    viewModel.completeTaskAtDate(
                        taskId = ds.taskId,
                        isoDate = isoDate,
                        completedBy = participantIds.first(),
                        previewPoints = preview?.finalPoints,
                        previewReason = preview?.reason,
                    )
                } else {
                    viewModel.completeTaskCollective(
                        taskId = ds.taskId,
                        participantUserIds = participantIds,
                        isoDate = isoDate,
                        previewPoints = preview?.finalPoints,
                        previewReason = preview?.reason,
                    )
                }
                dialogState = null
            },
            previewProvider = { isoDate, numParticipants ->
                viewModel.fetchCompletionPreview(ds.taskId, isoDate, numParticipants)
            },
            gamificationVisible = gamificationVisible,
        )
    }

    // « ! » À faire aujourd'hui (docs/plans/a-faire-aujourdhui.md).
    val todayOn = moduleEnabled(Modules.TODAY)
    val delegations by viewModel.today.byTask.collectAsState()
    val todayActions = rememberTodayActionState()
    val memberNames = remember(users) { users.associate { it.id to it.displayName } }
    // Titles of tasks a widget « ! » opened: the tab's filters may hide them from uiState.tasks.
    val widgetTitles = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    TodayActionDialogs(
        state = todayActions,
        titleOf = { id -> uiState.tasks.firstOrNull { it.task.id == id }?.task?.title ?: widgetTitles[id].orEmpty() },
        names = memberNames,
        me = currentUserId.orEmpty(),
        onHand = viewModel.today::hand,
    )

    // Recharger les donnees quand on revient sur cet ecran
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Afficher les messages de completion ou d'erreur
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

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Un seul bouton : les propositions du catalogue vivent desormais
            // DANS l'ecran de creation, filtrees par les contextes du foyer.
            // Le deuxieme bouton menait a un ecran que rien ne cablait — il
            // existait a l'ecran sans rien faire.
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(
                    onClick = onCreateTask,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.task_list_add_task_cd),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        // contentWindowInsets = 0 : le Scaffold parent dans NavGraph.MainScreen
        // consomme deja les system bars (status + nav bottom) via son bottomBar
        // NavigationBar. Sans cette ligne, on double-consomme l'inset top et
        // les chips de filtres se retrouvent loin du status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Barre de sync fine en haut, visible pendant chaque refresh
            // (initial, pull-to-refresh, ou retour sur l'ecran).
            if (uiState.isRefreshing || uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                // Reserve la place pour eviter le saut visuel quand la barre disparait.
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Bandeau "pas a la maison"
            if (!uiState.isAtHome && uiState.homeTasksHidden > 0) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.task_list_off_home_banner_format, uiState.homeTasksHidden),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        InfoTooltip(
                            title = stringResource(R.string.task_list_off_home_tooltip_title),
                            description = stringResource(R.string.task_list_off_home_tooltip_description),
                        )
                        IconButton(
                            onClick = onNavigateToFoyerSettings,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.task_list_foyer_settings_cd),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.toggleShowAll() }
                        ) {
                            Text(
                                if (uiState.showAllTasks) stringResource(R.string.task_list_hide_home)
                                else stringResource(R.string.task_list_show_all)
                            )
                        }
                    }
                }
            }

            // Barre de recherche OU barre des contextes (avec la loupe a droite). La recherche
            // atteint toutes les taches, a jour comprises, sans filtre de contexte (applyFilters).
            val searchFocusRequester = remember { FocusRequester() }
            LaunchedEffect(uiState.isSearchBarExpanded) {
                if (uiState.isSearchBarExpanded) {
                    try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
                }
            }

            if (uiState.isSearchBarExpanded) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(stringResource(R.string.task_list_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.closeSearchBar() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.task_list_search_close_cd),
                            )
                        }
                    },
                    singleLine = true,
                )
            } else {
                TaskListContextBar(
                    contexts = uiState.availableContexts,
                    selectedIds = uiState.selectedContextIds,
                    onClear = viewModel::clearContextFilter,
                    onToggle = viewModel::toggleContextFilter,
                    onSearch = viewModel::openSearchBar,
                )
            }

            // Contenu principal — pas de loading central, c'est la barre fine
            // en haut qui signale le sync. Le pattern stale-while-revalidate
            // affiche le cache si dispo, sinon empty state ou message backend HS.
            when {
                uiState.tasks.isEmpty() && !uiState.isRefreshing && uiState.searchQuery.isNotBlank() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.task_list_no_search_results_format, uiState.searchQuery.trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Nothing to do (the tasks up to date folded): the empty state, and under it the
                // button that unfolds them when there are any.
                !uiState.searching && !uiState.isRefreshing && uiState.blocks.toDoCount == 0 &&
                    !(uiState.showUpToDate && uiState.blocks.upToDate.isNotEmpty()) -> {
                    val backendUnreachable = uiState.lastRefreshFailed && !uiState.hasCachedData
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.task_list_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (uiState.blocks.upToDate.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                UpToDateToggleButton(
                                    count = uiState.blocks.upToDate.size, expanded = false,
                                    onClick = viewModel::toggleUpToDate,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }

                else -> {
                    // Callbacks stables : on les memoise une SEULE fois (sans key
                    // sur les donnees) pour qu'elles ne changent jamais. Sinon
                    // chaque emission du combine du ViewModel = nouvelles lambdas
                    // = tous les items recomposent (~50 cards) = scroll qui rame.
                    // rememberUpdatedState permet aux lambdas historical/ensemble
                    // de lire la valeur courante sans s'invalider.
                    val tasksState = androidx.compose.runtime.rememberUpdatedState(uiState.tasks)
                    val usersState = androidx.compose.runtime.rememberUpdatedState(users)
                    val onCardClick: (String) -> Unit = remember {
                        { id -> onTaskClick(id) }
                    }
                    val onCardComplete: (String) -> Unit = remember {
                        { id -> viewModel.completeTask(id) }
                    }
                    val onCardHistorical: (String) -> Unit = remember {
                        { id ->
                            val task = tasksState.value.firstOrNull { it.task.id == id }?.task
                            dialogState = TaskListCompletionDialogState(
                                taskId = id,
                                taskTitle = task?.title.orEmpty(),
                                assignedIds = task?.assignedToIds?.toSet() ?: emptySet(),
                            )
                        }
                    }
                    val delegationsState = androidx.compose.runtime.rememberUpdatedState(delegations)
                    val meState = androidx.compose.runtime.rememberUpdatedState(currentUserId.orEmpty())
                    val onCardToday: (String) -> Unit = remember {
                        { id ->
                            val task = tasksState.value.firstOrNull { it.task.id == id }?.task
                            todayActions.onTaskTap(
                                id, task?.assignedToIds.orEmpty(), delegationsState.value[id],
                                usersState.value.map { it.id }, meState.value, viewModel.today,
                            )
                        }
                    }
                    // A widget button lands here (WidgetLaunch): same dialog as the card's, its task
                    // read from the whole cache, which the tab's filters do not narrow.
                    val widgetRequest by com.torchons.app.widget.WidgetLaunch.request.collectAsState()
                    val appContext = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(widgetRequest, users.isNotEmpty()) {
                        val req = widgetRequest?.takeIf { users.isNotEmpty() } ?: return@LaunchedEffect
                        val task = findWidgetTask(appContext, req.taskId)
                        com.torchons.app.widget.WidgetLaunch.consume()
                        when (req.kind) {
                            com.torchons.app.widget.WidgetLaunch.Kind.DATE_WHO ->
                                dialogState = widgetCompletionDialog(req, task)
                            com.torchons.app.widget.WidgetLaunch.Kind.TODAY -> {
                                task?.let { widgetTitles[it.id] = it.title }
                                todayActions.onTaskTap(
                                    req.taskId, task?.assignedToIds.orEmpty(), delegationsState.value[req.taskId],
                                    users.map { it.id }, meState.value, viewModel.today,
                                )
                            }
                        }
                    }

                    // Liste des taches avec pull-to-refresh
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val rows = taskListRows(uiState.blocks, uiState.showUpToDate, uiState.searching)
                            items(
                                items = rows,
                                key = { row -> rowKey(row) },
                                contentType = { row -> row::class.simpleName },
                            ) { row ->
                                if (row !is TaskListRow.Item) {
                                    TaskListRowExtra(row, onToggleUpToDate = viewModel::toggleUpToDate)
                                    return@items
                                }
                                val taskWithUrgency = row.task
                                val celebrating = uiState.celebratingTaskId == taskWithUrgency.task.id
                                TaskCard(
                                    taskWithUrgency = taskWithUrgency,
                                    isCelebrating = celebrating,
                                    onClick = onCardClick,
                                    onComplete = onCardComplete,
                                    onHistorical = onCardHistorical,
                                    today = todayCardState(
                                        androidx.compose.ui.platform.LocalContext.current.resources,
                                        taskWithUrgency.task.assignedToIds, delegations[taskWithUrgency.task.id],
                                        memberNames, currentUserId.orEmpty(),
                                    ).takeIf { todayOn },
                                    onToday = onCardToday,
                                    // Points + attribution seulement sur la carte en
                                    // celebration, et masques si gamif opt-out (v0.32).
                                    celebrationPoints = if (celebrating && gamificationVisible) uiState.celebrationPoints else null,
                                    celebrationReason = if (celebrating) uiState.celebrationReason else null,
                                    celebrationEarnedBy = if (celebrating && gamificationVisible) uiState.celebrationEarnedBy else null,
                                )
                            }

                            // Pas de spacer bottom : la decision 4c (FAB cache) est volontairement
                            // non corrigee a la demande de l'utilisateur.
                        }
                    }
                }
            }
        }
    }

    // Overlay konfetti plein ecran — declenche par celebrationTrigger.
    androidx.compose.runtime.key(uiState.celebrationTrigger) {
        CelebrationOverlay(isCelebrating = uiState.celebratingTaskId != null)
    }
    } // fin du Box outer (celebration overlay layer)
}


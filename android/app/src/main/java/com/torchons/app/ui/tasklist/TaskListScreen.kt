package com.torchons.app.ui.tasklist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.util.FreshnessCalculator

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
    viewModel: TaskListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var historicalTaskId by remember { mutableStateOf<String?>(null) }
    var historicalUserId by remember { mutableStateOf<String?>(null) }
    var showUserPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val users by viewModel.allUsers.collectAsState()

    // 1. User picker pour "deja fait" : qui a fait ?
    if (showUserPicker && historicalTaskId != null) {
        // Filtrer les users assignes a cette tache
        val task = uiState.tasks.find { it.task.id == historicalTaskId }?.task
        val assignedIds = task?.assignedToIds ?: emptyList()
        val eligibleUsers = users.filter { it.id in assignedIds }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUserPicker = false; historicalTaskId = null },
            title = { Text("Qui a fait cette tache ?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    eligibleUsers.forEach { user ->
                        androidx.compose.material3.Button(
                            onClick = {
                                historicalUserId = user.id
                                showUserPicker = false
                                showDatePicker = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(user.displayName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUserPicker = false; historicalTaskId = null }) { Text("Annuler") }
            }
        )
    }

    // 2. Date picker pour "deja fait" : quand ?
    if (showDatePicker && historicalTaskId != null && historicalUserId != null) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false; historicalTaskId = null; historicalUserId = null },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    val tId = historicalTaskId
                    val uId = historicalUserId
                    if (millis != null && tId != null && uId != null) {
                        val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        viewModel.completeTaskAtDate(tId, "${date}T12:00:00Z", uId)
                    }
                    showDatePicker = false
                    historicalTaskId = null
                    historicalUserId = null
                }) { Text("Valider") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDatePicker = false; historicalTaskId = null; historicalUserId = null }) { Text("Annuler") }
            }
        ) { DatePicker(state = datePickerState) }
    }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateTask,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Ajouter une tache",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        // Pas de topBar : l'ancien bandeau "Torchons & Serviettes" + avatar
        // a ete retire (rien de cliquable dedans). Le Scaffold gere automatiquement
        // l'inset top via paddingValues.calculateTopPadding() quand topBar est absent.
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
                            text = "Pas a la maison — ${uiState.homeTasksHidden} tache(s) masquee(s)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.toggleShowAll() }
                        ) {
                            Text(if (uiState.showAllTasks) "Masquer" else "Tout afficher")
                        }
                    }
                }
            }

            // Chips de filtres avancés + label — premiere rangee colle au top
            // (juste sous la barre de sync). Le Scaffold gere le status bar inset.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterIconChip(
                    icon = "🔥",
                    active = uiState.filterTodo,
                    color = Color(0xFFEF4444),
                    onClick = { viewModel.toggleFilterTodo() }
                )
                FilterIconChip(
                    icon = "❤",
                    state = uiState.filterAffinity,
                    onClick = { viewModel.toggleFilterAffinity() }
                )
                FilterIconChip(
                    icon = "⏱",
                    state = uiState.filterEffort,
                    onClick = { viewModel.toggleFilterEffort() }
                )
                FilterIconChip(
                    icon = "🔁",
                    state = uiState.filterRecurrence,
                    onClick = { viewModel.toggleFilterRecurrence() }
                )
                FilterIconChip(
                    icon = "🏠",
                    state = uiState.filterHome,
                    onClick = { viewModel.toggleFilterHome() }
                )

                // Label du filtre actif
                val filterLabel = buildString {
                    val parts = mutableListOf<String>()
                    if (uiState.filterTodo) parts += "🔥 A faire"
                    when (uiState.filterAffinity) {
                        FilterState.HIGH -> parts += "❤ Aimees"
                        FilterState.LOW -> parts += "❤ Detestees"
                        else -> {}
                    }
                    when (uiState.filterEffort) {
                        FilterState.HIGH -> parts += "⏱ Long"
                        FilterState.LOW -> parts += "⏱ Court"
                        else -> {}
                    }
                    when (uiState.filterRecurrence) {
                        FilterState.HIGH -> parts += "🔁 Recurrentes"
                        FilterState.LOW -> parts += "🔁 Ponctuelles"
                        else -> {}
                    }
                    when (uiState.filterHome) {
                        FilterState.HIGH -> parts += "🏠 Maison"
                        FilterState.LOW -> parts += "🏠 Hors maison"
                        else -> {}
                    }
                    append(parts.joinToString(" · "))
                }
                if (filterLabel.isNotEmpty()) {
                    Text(
                        text = filterLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Chips de filtrage par categorie — espacement uniforme 6dp
            if (uiState.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Chip "Toutes" - clear le filtre
                    FilterChip(
                        selected = uiState.selectedCategoryIds.isEmpty(),
                        onClick = { viewModel.clearCategoryFilter() },
                        label = { Text("Toutes") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )

                    // Chips pour chaque categorie qui contient au moins une tache.
                    // Memoise pour eviter le O(catégories × taches) a chaque
                    // recompose (changement de filtre, scroll, etc.).
                    val categoriesWithTasks = remember(
                        uiState.categories,
                        uiState.tasks,
                        uiState.selectedCategoryIds,
                    ) {
                        val taskCategoryIds = uiState.tasks.mapNotNullTo(mutableSetOf()) { it.task.categoryId }
                        uiState.categories.filter { cat ->
                            cat.id in taskCategoryIds || cat.id in uiState.selectedCategoryIds
                        }
                    }
                    categoriesWithTasks.forEach { category ->
                        val catColor = try {
                            Color(android.graphics.Color.parseColor(category.color))
                        } catch (e: Exception) {
                            MaterialTheme.colorScheme.primary
                        }

                        FilterChip(
                            selected = category.id in uiState.selectedCategoryIds,
                            onClick = { viewModel.toggleCategory(category.id) },
                            label = { Text(category.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = catColor,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // Chips de filtrage par piece (extraite du titre) — meme espacement uniforme
            if (uiState.availableRooms.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.selectedRoom == null,
                        onClick = { viewModel.selectRoom(null) },
                        label = { Text("Toutes pieces") }
                    )
                    uiState.availableRooms.forEach { room ->
                        FilterChip(
                            selected = uiState.selectedRoom == room,
                            onClick = { viewModel.selectRoom(room) },
                            label = { Text(room) }
                        )
                    }
                }
            }

            // Contenu principal — pas de loading central, c'est la barre fine
            // en haut qui signale le sync. Le pattern stale-while-revalidate
            // affiche le cache si dispo, sinon empty state ou message backend HS.
            when {
                uiState.tasks.isEmpty() && !uiState.isRefreshing -> {
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Aucune tache a afficher",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
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
                    // rememberUpdatedState permet a la lambda historical de lire
                    // la valeur courante de tasks sans s'invalider.
                    val tasksState = androidx.compose.runtime.rememberUpdatedState(uiState.tasks)
                    val onCardClick: (String) -> Unit = remember {
                        { id -> onTaskClick(id) }
                    }
                    val onCardComplete: (String) -> Unit = remember {
                        { id -> viewModel.completeTask(id) }
                    }
                    val onCardHistorical: (String) -> Unit = remember {
                        { id ->
                            val task = tasksState.value.firstOrNull { it.task.id == id }?.task
                            historicalTaskId = id
                            if (task != null && task.assignedToIds.size == 1) {
                                historicalUserId = task.assignedToIds.first()
                                showDatePicker = true
                            } else {
                                showUserPicker = true
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
                            items(
                                items = uiState.tasks,
                                key = { it.task.id },
                                contentType = { "task_card" },
                            ) { taskWithFreshness ->
                                TaskCard(
                                    taskWithFreshness = taskWithFreshness,
                                    onClick = onCardClick,
                                    onComplete = onCardComplete,
                                    onHistorical = onCardHistorical,
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
}

/** Chip icone pour filtre tri-state (off / high rouge / low vert). */
@Composable
private fun FilterIconChip(
    icon: String,
    state: FilterState,
    onClick: () -> Unit
) {
    val bgColor = when (state) {
        FilterState.OFF -> MaterialTheme.colorScheme.surfaceVariant
        FilterState.HIGH -> Color(0xFFEF4444)
        FilterState.LOW -> Color(0xFF10B981)
    }
    val textColor = if (state == FilterState.OFF) MaterialTheme.colorScheme.onSurfaceVariant else Color.White

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = icon,
            modifier = Modifier.align(Alignment.Center),
            color = textColor,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** Chip icone pour filtre simple on/off. */
@Composable
private fun FilterIconChip(
    icon: String,
    active: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bgColor = if (active) color else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = icon,
            modifier = Modifier.align(Alignment.Center),
            color = textColor,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

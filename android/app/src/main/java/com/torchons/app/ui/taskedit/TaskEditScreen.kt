package com.torchons.app.ui.taskedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.ui.common.FrequencyVoteDialog
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit

/**
 * Ecran de creation ou edition d'une tache.
 * - taskId != null  => mode edition
 * - cloneFromId != null (et taskId == null) => mode creation par clonage,
 *   pre-remplit le formulaire depuis la tache source.
 * - les deux null => mode creation neuve.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditScreen(
    taskId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit = {},
    cloneFromId: String? = null,
    viewModel: TaskEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val titleFocusRequester = remember { FocusRequester() }

    // Charger les donnees au lancement
    LaunchedEffect(taskId, cloneFromId) {
        viewModel.loadData(taskId, cloneFromId)
    }

    // Focus + select-all sur le titre quand on entre en mode clone, pour que
    // l'user puisse renommer rapidement (ex: "...salon" -> "...chambre") sans
    // avoir a effacer manuellement le texte pre-rempli.
    LaunchedEffect(uiState.clonedFromTitle) {
        if (uiState.clonedFromTitle != null) {
            titleFocusRequester.requestFocus()
        }
    }

    // Naviguer en arriere apres sauvegarde
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    // Naviguer a la liste apres suppression
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
    }

    // Afficher les erreurs
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Mini-dialog de re-vote frequence (ouvert depuis le bouton "Modifier
    // via le vote" sous le champ every_n_days grise).
    val freqVotes = uiState.frequencyVotes
    if (uiState.showFrequencyVoteDialog && taskId != null && freqVotes != null) {
        FrequencyVoteDialog(
            taskTitle = uiState.title,
            state = freqVotes,
            onSubmit = { request -> viewModel.submitFrequencyVote(taskId, request) },
            onDismiss = { viewModel.dismissFrequencyVoteDialog() },
        )
    }

    // Dialog de confirmation de suppression
    if (showDeleteDialog && taskId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer la tache ?") },
            text = { Text("Cette action est irreversible. La tache sera supprimee pour tous les utilisateurs.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(taskId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isEditMode) "Modifier la tache" else "Nouvelle tache",
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
                    if (uiState.isEditMode && taskId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Chip "Duplique de [titre]" en mode creation par clonage.
            // Tap sur le chip = annuler la duplication (vide tous les champs).
            uiState.clonedFromTitle?.let { src ->
                AssistChip(
                    onClick = { viewModel.resetClone() },
                    label = { Text("Duplique de : $src") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Annuler la duplication",
                            modifier = Modifier.height(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }

            // Titre — TextFieldValue local pour pouvoir piloter la selection
            // (select-all au montage en mode clone, fin de texte sinon).
            var titleFieldValue by remember { mutableStateOf(TextFieldValue(uiState.title)) }
            LaunchedEffect(uiState.title, uiState.clonedFromTitle) {
                if (titleFieldValue.text != uiState.title) {
                    titleFieldValue = TextFieldValue(
                        text = uiState.title,
                        selection = if (uiState.clonedFromTitle != null) {
                            TextRange(0, uiState.title.length)
                        } else {
                            TextRange(uiState.title.length)
                        },
                    )
                }
            }
            OutlinedTextField(
                value = titleFieldValue,
                onValueChange = { newValue ->
                    titleFieldValue = newValue
                    if (newValue.text != uiState.title) {
                        viewModel.updateTitle(newValue.text)
                    }
                },
                label = { Text("Titre *") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocusRequester),
                singleLine = true,
            )

            // Dropdown des suggestions de duplication. Visible uniquement en
            // creation neuve (pas en edition, pas avec un clone deja applique)
            // et a partir de 2 caracteres tapes — geres dans le ViewModel.
            if (suggestions.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column {
                        Text(
                            text = "Dupliquer une tache existante :",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        suggestions.forEach { sugg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.applyCloneFrom(sugg.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.height(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = sugg.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Description
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Categorie
            var categoryExpanded by remember { mutableStateOf(false) }
            val selectedCategory = uiState.categories.find { it.id == uiState.categoryId }

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedCategory?.name ?: "Aucune",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Categorie") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Aucune") },
                        onClick = {
                            viewModel.updateCategoryId(null)
                            categoryExpanded = false
                        }
                    )
                    uiState.categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = {
                                viewModel.updateCategoryId(cat.id)
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            // Piece (room)
            var roomExpanded by remember { mutableStateOf(false) }
            val selectedRoom = uiState.rooms.find { it.id == uiState.roomId }

            ExposedDropdownMenuBox(
                expanded = roomExpanded,
                onExpandedChange = { roomExpanded = it }
            ) {
                val roomLabel = selectedRoom?.let {
                    if (!it.emoji.isNullOrBlank()) "${it.emoji} ${it.name}" else it.name
                } ?: "Aucune"
                OutlinedTextField(
                    value = roomLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Piece") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roomExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = roomExpanded,
                    onDismissRequest = { roomExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Aucune") },
                        onClick = {
                            viewModel.updateRoomId(null)
                            roomExpanded = false
                        }
                    )
                    uiState.rooms.forEach { r ->
                        val label = if (!r.emoji.isNullOrBlank()) "${r.emoji} ${r.name}" else r.name
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.updateRoomId(r.id)
                                roomExpanded = false
                            }
                        )
                    }
                }
            }

            // Assignes
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "Assigne a",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = "Assignation",
                    description = "Selectionne qui doit faire cette tache. Si tous les membres sont coches, c'est une tache commune. Si un seul est coche, c'est une tache personnelle visible uniquement par cette personne."
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.users.forEach { user ->
                    FilterChip(
                        selected = uiState.assignedToIds.contains(user.id),
                        onClick = { viewModel.toggleUserAssignment(user.id) },
                        label = { Text(user.displayName) }
                    )
                }
            }

            // Recurrence — editable en creation ET en edition. Le vote individuel
            // (RatingDialog depuis TaskDetail / home) reste une autre voie pour
            // ajuster la cadence d'un interval, mais changer de type (ex. passer
            // d'interval a uncertain) se fait ici.
            var recurrenceExpanded by remember { mutableStateOf(false) }
            // v0.27 — libelles ***REMOVED***-friendly :
            // - interval -> Reguliere
            // - monthly_variable -> Selon le mois
            // - uncertain -> Irreguliere
            // - none -> Une seule fois
            val recurrenceLabel = when (uiState.recurrenceType) {
                "interval" -> "Reguliere"
                "monthly_variable" -> "Selon le mois"
                "uncertain" -> "Irreguliere"
                "none" -> "Une seule fois"
                else -> uiState.recurrenceType
            }

            ExposedDropdownMenuBox(
                expanded = recurrenceExpanded,
                onExpandedChange = { recurrenceExpanded = it }
            ) {
                OutlinedTextField(
                    value = recurrenceLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Frequence") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = recurrenceExpanded,
                    onDismissRequest = { recurrenceExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Reguliere") },
                        onClick = {
                            viewModel.updateRecurrenceType("interval")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Selon le mois") },
                        onClick = {
                            viewModel.updateRecurrenceType("monthly_variable")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Irreguliere") },
                        onClick = {
                            viewModel.updateRecurrenceType("uncertain")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Une seule fois") },
                        onClick = {
                            viewModel.updateRecurrenceType("none")
                            recurrenceExpanded = false
                        }
                    )
                }
            }

            // Nombre de jours si interval. Si la tache a deja >=1 vote de
            // frequence, le champ est grise et seul le mini-dialog "Modifier
            // via le vote" peut le modifier (decision Q1+Q5 du grilling :
            // every_n_days est pilote par les votes en mode interval).
            // L'utilisateur peut basculer l'unite de saisie entre Jours,
            // Semaines et Mois (1 sem = 7 j, 1 mois = 30 j) via le segmented
            // button. Le breakdown smart sous le champ ("3 mois 2 j") aide
            // a se reperer quand la valeur ne tombe pas pile sur l'unite.
            if (uiState.recurrenceType == "interval") {
                val votes = uiState.frequencyVotes
                val isVoteLocked = votes != null && votes.votesCount >= 1
                val unit = uiState.recurrenceUnit
                val canonicalDays = uiState.recurrenceFieldText.toIntOrNull()
                    ?.let { unit.toDays(it) } ?: 7

                // Toggle d'unite [Jours | Semaines | Mois]
                val units = DurationUnit.values().toList()
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    units.forEachIndexed { idx, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = { viewModel.updateRecurrenceUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                            enabled = !isVoteLocked,
                        ) { Text(u.label) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = uiState.recurrenceFieldText,
                    onValueChange = { viewModel.updateRecurrenceFieldText(it) },
                    label = { Text("Tous les combien de ${unit.label.lowercase()} ?") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isVoteLocked,
                )
                // Breakdown smart sous le champ — seulement si la valeur
                // depasse l'unite (eviter "1 mois ≈ 1 mois").
                if (canonicalDays >= 7) {
                    val human = DurationFormat.formatDaysHuman(canonicalDays, long = true)
                    Text(
                        text = "≈ $human ($canonicalDays j)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (isVoteLocked) {
                    val s = if (votes!!.votesCount > 1) "s" else ""
                    val consensusDays = votes.consensusEveryNDays ?: 0
                    val consensusHuman = if (consensusDays > 0)
                        DurationFormat.formatDaysHuman(consensusDays) else "?"
                    Text(
                        text = "Consensus : $consensusHuman (${votes.votesCount} vote$s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    if (votes.canVote) {
                        OutlinedButton(
                            onClick = { viewModel.openFrequencyVoteDialog() },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Modifier mon vote")
                        }
                    }
                }
            }

            // Tableau 12 mois si monthly_variable
            if (uiState.recurrenceType == "monthly_variable") {
                val monthNames = listOf("Jan", "Fev", "Mar", "Avr", "Mai", "Jun", "Jul", "Aou", "Sep", "Oct", "Nov", "Dec")
                Text(
                    text = "Jours entre chaque repetition par mois (vide = inactif)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 2 colonnes de 6 mois
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..6).forEach { m ->
                            OutlinedTextField(
                                value = uiState.daysPerMonth[m.toString()] ?: "",
                                onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                                label = { Text(monthNames[m - 1]) },
                                singleLine = true
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (7..12).forEach { m ->
                            OutlinedTextField(
                                value = uiState.daysPerMonth[m.toString()] ?: "",
                                onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                                label = { Text(monthNames[m - 1]) },
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Tolerance au retard : uniquement pour les recurrences a cadence
            // definie (interval, monthly_variable). Cachee pour 'none' (ponctuelle)
            // et 'uncertain' (sans cadence) qui n'utilisent pas le tier tolerance
            // dans le selecteur d'urgence du home.
            if (uiState.recurrenceType == "interval" || uiState.recurrenceType == "monthly_variable") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tolerance au retard",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = "A quoi sert la tolerance ?",
                        description = com.torchons.app.ui.common.TOLERANCE_INFO_TEXT,
                    )
                }
                val tolerances = listOf(
                    "STRICT" to "Strict",
                    "NORMAL" to "Normal",
                    "SOUPLE" to "Souple",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    tolerances.forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            selected = uiState.tolerance == value,
                            onClick = { viewModel.updateTolerance(value) },
                            shape = SegmentedButtonDefaults.itemShape(idx, tolerances.size),
                        ) { Text(label) }
                    }
                }
            }

            // Effort (temps estime)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val minutes = uiState.weight * 5
                val timeLabel = if (minutes >= 60) "${minutes / 60}h${if (minutes % 60 > 0) "%02d".format(minutes % 60) else ""}" else "${minutes}min"
                Text(
                    text = "Effort : $timeLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = "Effort",
                    description = "Estimation du temps que prend cette tache. Chaque utilisateur pourra donner sa propre estimation, la moyenne sera utilisee pour calculer les points."
                )
            }
            Slider(
                value = uiState.weight.toFloat(),
                onValueChange = { viewModel.updateWeight(it.toInt()) },
                valueRange = 1f..18f,
                steps = 16,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bouton sauvegarder
            Button(
                onClick = { viewModel.save(taskId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.height(24.dp).width(24.dp)
                    )
                } else {
                    Text(
                        text = if (uiState.isEditMode) "Enregistrer" else "Creer la tache",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bouton supprimer en mode edition
            if (uiState.isEditMode && taskId != null) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Supprimer la tache",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

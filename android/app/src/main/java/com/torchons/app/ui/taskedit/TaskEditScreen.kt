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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.torchons.app.ui.common.FrequencyVoteDialog
import com.torchons.app.ui.common.recurrenceSentence
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
            header = com.torchons.app.ui.common.VoteTaskHeader(uiState.title),
            state = freqVotes,
            onSubmit = { request -> viewModel.submitFrequencyVote(taskId, request) },
            onDismiss = { viewModel.dismissFrequencyVoteDialog() },
        )
    }

    // Dialog de confirmation de suppression
    if (showDeleteDialog && taskId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.task_edit_delete_dialog_title)) },
            text = { Text(stringResource(R.string.task_edit_delete_dialog_message)) },
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
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
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
                        text = if (uiState.isEditMode) stringResource(R.string.task_edit_title_edit)
                        else stringResource(R.string.task_edit_title_new),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.isEditMode && taskId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.task_edit_delete_cd),
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
                    label = { Text(stringResource(R.string.task_edit_cloned_from_format, src)) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.task_edit_cancel_clone_cd),
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
                label = { Text(stringResource(R.string.task_edit_field_title)) },
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
                            text = stringResource(R.string.task_edit_suggestions_header),
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

            // Propositions du catalogue — ce que le foyer PEUT avoir vu ses
            // contextes, et qu'il n'a pas encore. Sans filtre par contexte, ce
            // serait le catalogue brut : 181 lignes dont l'immense majorite ne
            // concerne pas ce foyer, et une liste qu'on referme.
            //
            // Le champ titre filtre la liste au lieu de la remplacer : taper
            // « litiere » restreint aux propositions correspondantes, et ce
            // qu'on choisit remplit la frequence, l'effort et les contextes
            // d'un coup.
            val propositions = viewModel.matchingSuggestions(uiState.title)
            if (!uiState.isEditMode && uiState.appliedSuggestion == null &&
                propositions.isNotEmpty()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.task_edit_catalog_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        propositions.take(6).forEach { proposition ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.applySuggestion(proposition) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proposition.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    proposition.contextLabel?.let { label ->
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bandeau « rempli depuis une proposition », avec sortie de secours.
            uiState.appliedSuggestion?.let { titre ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.task_edit_catalog_applied_format, titre),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.clearSuggestion() }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            }

            // Description
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text(stringResource(R.string.task_edit_field_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Contextes : une tache en porte PLUSIEURS (TaskContextsField.kt).
            TaskContextsField(
                contexts = uiState.contexts,
                selectedIds = uiState.contextIds,
                onToggle = viewModel::toggleContext,
            )

            // Assignes
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.task_edit_field_assigned),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = stringResource(R.string.task_edit_assigned_tooltip_title),
                    description = stringResource(R.string.task_edit_assigned_tooltip_description),
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
            // v0.27 — libelles plus lisibles (UX tests internes) :
            // - interval -> Reguliere
            // - monthly_variable -> Selon le mois
            // - uncertain -> Irreguliere
            // - none -> Une seule fois
            val recurrenceLabel = when (uiState.recurrenceType) {
                "interval" -> stringResource(R.string.task_edit_recurrence_interval)
                "monthly_variable" -> stringResource(R.string.task_edit_recurrence_monthly)
                "uncertain" -> stringResource(R.string.task_edit_recurrence_uncertain)
                "calendar" -> stringResource(R.string.task_edit_recurrence_calendar)
                "anniversary" -> stringResource(R.string.task_edit_recurrence_anniversary)
                "none" -> stringResource(R.string.task_edit_recurrence_none)
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
                    label = { Text(stringResource(R.string.task_edit_field_recurrence)) },
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
                        text = { Text(stringResource(R.string.task_edit_recurrence_interval)) },
                        onClick = {
                            viewModel.updateRecurrenceType("interval")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit_recurrence_monthly)) },
                        onClick = {
                            viewModel.updateRecurrenceType("monthly_variable")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit_recurrence_uncertain)) },
                        onClick = {
                            viewModel.updateRecurrenceType("uncertain")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit_recurrence_calendar)) },
                        onClick = {
                            viewModel.updateRecurrenceType("calendar")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit_recurrence_anniversary)) },
                        onClick = {
                            viewModel.updateRecurrenceType("anniversary")
                            recurrenceExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_edit_recurrence_none)) },
                        onClick = {
                            viewModel.updateRecurrenceType("none")
                            recurrenceExpanded = false
                        }
                    )
                }
            }

            // Type `calendar` : les mois d'echeance, le jour, la tolerance.
            // Pas de vote de frequence ici — une date fixe n'est pas une
            // cadence a estimer, c'est un fait du calendrier.
            if (uiState.recurrenceType == "calendar") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.task_edit_calendar_months_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                val moisCourts = stringArrayResource(R.array.month_names_short)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..12).forEach { m ->
                        FilterChip(
                            selected = m in uiState.recurrenceMonths,
                            onClick = { viewModel.toggleRecurrenceMonth(m) },
                            label = { Text(moisCourts.getOrElse(m - 1) { "?" }) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.recurrenceDay.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::updateRecurrenceDay) },
                    label = { Text(stringResource(R.string.task_edit_calendar_day_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.recurrenceGraceDays.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::updateRecurrenceGraceDays) },
                    label = { Text(stringResource(R.string.task_edit_calendar_grace_label)) },
                    supportingText = {
                        Text(stringResource(R.string.task_edit_calendar_grace_help))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // `anniversary`: only the period. The anchor is the first recorded
            // completion, not a field: no vote either, the date is a fact.
            if (uiState.recurrenceType == "anniversary") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.anniversaryAmount.toString(),
                        onValueChange = { it.toIntOrNull()?.let(viewModel::updateAnniversaryAmount) },
                        label = { Text(stringResource(R.string.task_edit_anniversary_period_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = uiState.anniversaryInYears,
                        onClick = { viewModel.updateAnniversaryInYears(true) },
                        label = { Text(stringResource(R.string.task_edit_anniversary_unit_years)) },
                    )
                    FilterChip(
                        selected = !uiState.anniversaryInYears,
                        onClick = { viewModel.updateAnniversaryInYears(false) },
                        label = { Text(stringResource(R.string.task_edit_anniversary_unit_months)) },
                    )
                }
                Text(
                    text = stringResource(R.string.task_edit_anniversary_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // La phrase : elle explique le COMPORTEMENT (l'echeance glisse-t-elle
            // ou non ?), ce qu'aucun libelle de menu ne dit.
            uiState.previewRecurrenceRule()?.let { previsu ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = recurrenceSentence(previsu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

                val res = LocalContext.current.resources

                // Toggle d'unite [Jours | Semaines | Mois]
                val units = DurationUnit.values().toList()
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    units.forEachIndexed { idx, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = { viewModel.updateRecurrenceUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                            enabled = !isVoteLocked,
                        ) { Text(stringResource(u.labelRes)) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = uiState.recurrenceFieldText,
                    onValueChange = { viewModel.updateRecurrenceFieldText(it) },
                    label = { Text(stringResource(R.string.task_edit_every_n_format, stringResource(unit.labelRes).lowercase())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isVoteLocked,
                )
                // Breakdown smart sous le champ — seulement si la valeur
                // depasse l'unite (eviter "1 mois ≈ 1 mois").
                if (canonicalDays >= 7) {
                    val human = DurationFormat.formatDaysHuman(res, canonicalDays, long = true)
                    Text(
                        text = stringResource(R.string.task_edit_breakdown_format, human, canonicalDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (isVoteLocked) {
                    val consensusDays = votes!!.consensusEveryNDays ?: 0
                    val consensusHuman = if (consensusDays > 0)
                        DurationFormat.formatDaysHuman(res, consensusDays)
                    else stringResource(R.string.task_edit_consensus_unknown)
                    Text(
                        text = pluralStringResource(
                            R.plurals.task_edit_consensus_format,
                            votes.votesCount,
                            votes.votesCount,
                            consensusHuman,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    if (votes.canVote) {
                        OutlinedButton(
                            onClick = { viewModel.openFrequencyVoteDialog() },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.task_edit_edit_vote_button))
                        }
                    }
                }
            }

            // Tableau 12 mois si monthly_variable
            if (uiState.recurrenceType == "monthly_variable") {
                val monthNames = listOf(
                    stringResource(R.string.task_edit_month_jan),
                    stringResource(R.string.task_edit_month_feb),
                    stringResource(R.string.task_edit_month_mar),
                    stringResource(R.string.task_edit_month_apr),
                    stringResource(R.string.task_edit_month_may),
                    stringResource(R.string.task_edit_month_jun),
                    stringResource(R.string.task_edit_month_jul),
                    stringResource(R.string.task_edit_month_aug),
                    stringResource(R.string.task_edit_month_sep),
                    stringResource(R.string.task_edit_month_oct),
                    stringResource(R.string.task_edit_month_nov),
                    stringResource(R.string.task_edit_month_dec),
                )
                Text(
                    text = stringResource(R.string.task_edit_monthly_header),
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
                        text = stringResource(R.string.task_edit_field_tolerance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = stringResource(R.string.task_detail_tolerance_help_title),
                        description = com.torchons.app.ui.common.toleranceInfoText(),
                    )
                }
                val tolerances = listOf(
                    "STRICT" to stringResource(R.string.task_tolerance_strict),
                    "NORMAL" to stringResource(R.string.task_tolerance_normal),
                    "SOUPLE" to stringResource(R.string.task_tolerance_souple),
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
                val timeLabel = if (minutes >= 60) {
                    if (minutes % 60 > 0) stringResource(R.string.duration_hours_minutes_format, minutes / 60, minutes % 60)
                    else stringResource(R.string.duration_hours_only_format, minutes / 60)
                } else stringResource(R.string.duration_minutes_format, minutes)
                Text(
                    text = stringResource(R.string.task_edit_effort_format, timeLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                InfoTooltip(
                    title = stringResource(R.string.task_edit_effort_tooltip_title),
                    description = stringResource(R.string.task_edit_effort_tooltip_description),
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
                        text = if (uiState.isEditMode) stringResource(R.string.task_edit_save_button_edit)
                        else stringResource(R.string.task_edit_save_button_new),
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
                        text = stringResource(R.string.task_edit_delete_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

package com.torchons.app.ui.taskedit

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.ui.common.FrequencyVoteDialog
import com.torchons.app.ui.common.ImeSnackbarHost
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.PrimaryTopBar
import com.torchons.app.ui.common.dismissKeyboardThen
import com.torchons.app.ui.common.showUserError
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen

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

    val errorContext = LocalContext.current
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showUserError(errorContext, it, "TaskEdit")
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
        snackbarHost = { ImeSnackbarHost(snackbarHostState) },
        topBar = {
            PrimaryTopBar(
                title = {
                    Text(
                        text = if (uiState.isEditMode) stringResource(R.string.task_edit_title_edit)
                        else stringResource(R.string.task_edit_title_new),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                onBack = onBack,
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
                    HelpButton(HelpScreen.TASK_EDIT, tint = MaterialTheme.colorScheme.onPrimary)
                },
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

            // Contextes : une tache en porte PLUSIEURS (TaskContextsField.kt). Places
            // AVANT le titre : les cocher filtre les propositions du catalogue.
            TaskContextsField(
                contexts = uiState.contexts,
                selectedIds = uiState.contextIds,
                onToggle = viewModel::toggleContext,
            )

            TaskEditTitleSection(uiState, suggestions, viewModel, titleFocusRequester)

            // Description
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text(stringResource(R.string.task_edit_field_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
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

            TaskEditRecurrenceSection(uiState, viewModel)

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
                onClick = dismissKeyboardThen { viewModel.save(taskId) },
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

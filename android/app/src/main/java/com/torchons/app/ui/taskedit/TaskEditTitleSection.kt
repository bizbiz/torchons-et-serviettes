package com.torchons.app.ui.taskedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.Task
import com.torchons.app.ui.common.submitKeyboardActions

/** Title field, duplicate suggestions, catalog proposals and the applied-proposal banner. */
@Composable
internal fun TaskEditTitleSection(
    uiState: TaskEditUiState,
    suggestions: List<Task>,
    viewModel: TaskEditViewModel,
    titleFocusRequester: FocusRequester,
) {
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
        keyboardActions = submitKeyboardActions(),
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
    // Les contextes coches ET le champ titre filtrent la liste au lieu de la remplacer : taper
    // « litiere » restreint aux propositions correspondantes, et ce
    // qu'on choisit remplit la frequence, l'effort et les contextes
    // d'un coup.
    val propositions = viewModel.matchingSuggestions(uiState.title)
    if (!uiState.isEditMode && uiState.appliedSuggestion == null) {
        CatalogProposalsCard(
            proposals = propositions,
            onPick = viewModel::applySuggestion,
        )
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
}

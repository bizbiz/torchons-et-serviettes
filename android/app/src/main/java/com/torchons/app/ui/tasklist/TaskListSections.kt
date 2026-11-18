package com.torchons.app.ui.tasklist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.TaskContextRef

/*
 * Pieces of the Tasks tab (2026-11-18): the context bar with the search button, the italic
 * block headers and the « tâches à jour » button. The rows themselves: TaskListBlocks.kt.
 */

/**
 * Filtres par CONTEXTE — une seule barre la ou il y en avait deux (categories, puis pieces). Elles
 * posaient deux questions sur le meme objet et se combinaient en ET sans le dire. The search
 * button, which used to end the row of the six tri-state chips, ends this one.
 */
@Composable
internal fun TaskListContextBar(
    contexts: List<TaskContextRef>,
    selectedIds: Set<String>,
    onClear: () -> Unit,
    onToggle: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (contexts.isNotEmpty()) {
                FilterChip(
                    selected = selectedIds.isEmpty(),
                    onClick = onClear,
                    label = { Text(stringResource(R.string.task_list_filter_all_contexts)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            contexts.forEach { ctx ->
                val couleur = try {
                    Color(android.graphics.Color.parseColor(ctx.color))
                } catch (e: Exception) {
                    MaterialTheme.colorScheme.primary
                }
                FilterChip(
                    selected = ctx.id in selectedIds,
                    onClick = { onToggle(ctx.id) },
                    label = { Text(ctx.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = couleur,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.task_list_search_open_cd))
        }
    }
}

/** A stable LazyColumn key per row: the task id, or the header / button kind. */
internal fun rowKey(row: TaskListRow): String = when (row) {
    is TaskListRow.Item -> row.task.task.id
    is TaskListRow.Header -> "header-${row.kind}"
    is TaskListRow.UpToDateToggle -> "up-to-date-toggle"
}

/** A row that is not a task: an italic block header, or the « tâches à jour » button. */
@Composable
internal fun TaskListRowExtra(row: TaskListRow, onToggleUpToDate: () -> Unit) {
    when (row) {
        is TaskListRow.Header -> Text(
            text = stringResource(
                when (row.kind) {
                    HeaderKind.WITHOUT_CADENCE -> R.string.task_list_header_without_cadence
                    HeaderKind.UP_TO_DATE -> R.string.task_list_header_up_to_date
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        is TaskListRow.UpToDateToggle -> UpToDateToggleButton(row.count, row.expanded, onToggleUpToDate)
        is TaskListRow.Item -> Unit
    }
}

/** « Afficher les tâches à jour (N) », or « Masquer les tâches à jour » once unfolded. */
@Composable
internal fun UpToDateToggleButton(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(
            if (expanded) stringResource(R.string.task_list_hide_up_to_date)
            else stringResource(R.string.task_list_show_up_to_date_format, count)
        )
    }
}

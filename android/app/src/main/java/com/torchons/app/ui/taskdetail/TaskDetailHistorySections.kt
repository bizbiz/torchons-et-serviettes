package com.torchons.app.ui.taskdetail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.shouldShowEmptyState

/**
 * The two lists at the bottom of the task detail: the completions ("Historique") and the edits and
 * votes ("Modifications"). Split out of TaskDetailScreen.
 *
 * An empty list says "Aucun ..." only when the load really answered with nothing: after a refresh
 * that failed (offline, a task never opened online) it says the list is unavailable instead
 * (2026-11-20, item 6: an empty cache is not an empty history).
 */
internal fun LazyListScope.taskHistorySections(
    uiState: TaskDetailUiState,
    gamificationVisible: Boolean,
    viewModel: TaskDetailViewModel,
    onUserClick: (userId: String) -> Unit,
) {
    // Section historique
    item {
        SectionTitle(
            Icons.Default.History, R.string.task_detail_history_title_format, uiState.completions.size, 8.dp,
        )
    }

    if (uiState.completions.isEmpty()) {
        item { EmptyListText(uiState, R.string.task_detail_history_empty) }
    } else {
        // Grouping par collective_id : un evenement collectif =
        // une seule carte. Solo : carte = 1 row comme avant.
        // Pas de `remember` ici (LazyListScope n'est pas
        // @Composable) — le groupBy sur une liste courte est
        // negligeable en cout.
        val groups = groupCompletions(uiState.completions)
        items(
            items = groups,
            key = { it.key }
        ) { group ->
            CompletionCard(
                group = group,
                userNames = uiState.userNames,
                formerMembers = uiState.formerMembers, showPoints = gamificationVisible,
                onDeleteOne = { viewModel.deleteCompletion(group.head.id) },
                onDeleteCollective = {
                    viewModel.deleteCollective(group.rows.map { it.id })
                },
                onEdit = { viewModel.showEditCompletion(group.head) },
                onUserClick = onUserClick,
            )
        }
    }

    // Section historique des modifications (votes + edits)
    item {
        SectionTitle(
            Icons.Default.Edit, R.string.task_detail_modifications_title_format, uiState.history.size, 16.dp,
        )
    }

    if (uiState.history.isEmpty()) {
        item { EmptyListText(uiState, R.string.task_detail_modifications_empty) }
    } else {
        items(
            items = uiState.history,
            key = { it.id }
        ) { event ->
            HistoryEventCard(event = event)
        }
    }
}

@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleFormat: Int,
    count: Int,
    topPadding: androidx.compose.ui.unit.Dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = topPadding)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(titleFormat, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** "Aucun ..." when the list is really empty, "unavailable" when the refresh that fills it failed. */
@Composable
private fun EmptyListText(uiState: TaskDetailUiState, emptyText: Int) {
    if (uiState.isLoading) return
    val genuinelyEmpty = shouldShowEmptyState(uiState.isLoading, uiState.lastRefreshFailed, isEmpty = true)
    Text(
        text = stringResource(if (genuinelyEmpty) emptyText else R.string.task_detail_lists_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

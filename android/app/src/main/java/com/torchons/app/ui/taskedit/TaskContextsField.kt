package com.torchons.app.ui.taskedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.ContextView
import com.torchons.app.ui.common.InfoTooltip

/**
 * Contexts picker of the task editor. A task carries SEVERAL contexts:
 * "Clean the litter box" belongs to the Living room (where it is), the Cat
 * (why it exists) and Cleaning (what it is). Having to pick one would lose
 * two pieces of information out of three.
 *
 * Only the first selected context (★) drives the card display; all of them
 * count for filters and for the absence pause.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskContextsField(
    contexts: List<ContextView>,
    selectedIds: List<String>,
    onToggle: (String) -> Unit,
) {
    if (contexts.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.task_contexts_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            InfoTooltip(
                title = stringResource(R.string.task_contexts_tooltip_title),
                description = stringResource(R.string.task_contexts_tooltip_description),
            )
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            contexts.forEach { ctx ->
                val rank = selectedIds.indexOf(ctx.id)
                FilterChip(
                    selected = rank >= 0,
                    onClick = { onToggle(ctx.id) },
                    label = {
                        // Only the main context shows its rank: numbering them
                        // all would suggest an ordering to maintain, whereas
                        // only the first one matters.
                        Text(if (rank == 0) "★ " + ctx.label else ctx.label)
                    },
                )
            }
        }
    }
}

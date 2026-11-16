package com.torchons.app.ui.taskedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.CatalogSuggestion

private const val MAX_PROPOSALS_SHOWN = 6

/**
 * Card listing catalog proposals under the title field.
 *
 * The caller decides when to show it (creation only, nothing applied yet, and
 * a non-empty list): this composable never draws an empty frame itself, it
 * simply returns when [proposals] is empty.
 */
@Composable
fun CatalogProposalsCard(
    proposals: List<CatalogSuggestion>,
    onPick: (CatalogSuggestion) -> Unit,
) {
    if (proposals.isEmpty()) return
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
            proposals.take(MAX_PROPOSALS_SHOWN).forEach { proposal ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(proposal) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = proposal.title, style = MaterialTheme.typography.bodyMedium)
                        proposal.contextLabel?.let { label ->
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

package com.torchons.app.ui.taskdetail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * "Effort & Affinity" block of the task detail: each member's effort and affinity
 * ratings, the average, and the "+ info" links. These ratings belong to the
 * `repartition` module, so the caller shows the block only when it is on.
 * Moved out of TaskDetailScreen, which is over its size budget.
 *
 * The "Edit" button is offered only to assigned members, consistent with the
 * backend gate `_ensure_user_assigned`.
 */
@Composable
internal fun TaskRatingsSection(
    uiState: TaskDetailUiState,
    isCurrentUserAssigned: Boolean,
    currentUserId: String?,
    viewModel: TaskDetailViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.task_detail_section_effort_affinity),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (isCurrentUserAssigned) {
            TextButton(
                onClick = { viewModel.openRatingDialog(currentUserId) }
            ) { Text(stringResource(R.string.task_detail_edit_action)) }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))

    uiState.ratings.forEach { rating ->
        RatingRow(rating.displayName, rating.effortTimeMin, rating.affinity)
    }

    val avgMin = uiState.avgEffortTimeMin
    if (avgMin != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.task_detail_avg_score_format,
                formatMinutes(avgMin),
                uiState.scorePoints?.toString() ?: stringResource(R.string.task_detail_avg_score_unknown),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // v0.29: adaptive effort subline with a "+ info" link.
    if (uiState.effortVotes != null && uiState.effortVotes!!.votesCount > 0) {
        Spacer(modifier = Modifier.height(2.dp))
        MoreInfoLine(
            prefix = stringResource(R.string.task_detail_effort_points_label),
            onClick = { viewModel.openEffortInfo() },
        )
    }

    // v0.28: adaptive affinity subline with a "+ info" link.
    if (uiState.ratings.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        MoreInfoLine(
            prefix = stringResource(R.string.task_detail_affinity_label),
            onClick = { viewModel.openAffinityInfo() },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))
}

/** One member's line: name, effort estimate, affinity. */
@Composable
private fun RatingRow(name: String, effortTimeMin: Int?, affinity: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        if (effortTimeMin != null) {
            Text(
                text = formatMinutes(effortTimeMin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(50.dp)
            )
        } else {
            Text(
                stringResource(R.string.task_detail_no_value_dash),
                modifier = Modifier.width(50.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (affinity != null) {
            Text(
                text = stringResource(R.string.task_detail_affinity_value_format, affinity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(R.string.task_detail_affinity_value_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A muted prefix followed by an underlined "+ info" link. */
@Composable
private fun MoreInfoLine(prefix: String, onClick: () -> Unit) {
    val moreInfo = stringResource(R.string.task_detail_more_info_link)
    ClickableText(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(prefix)
            }
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
            ) {
                append(moreInfo)
            }
        },
        style = MaterialTheme.typography.labelSmall,
        onClick = { onClick() },
    )
}

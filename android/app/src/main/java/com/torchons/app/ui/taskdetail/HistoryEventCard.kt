package com.torchons.app.ui.taskdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType

/** Carte compacte affichant un evenement de l'historique de modification. */
@Composable
internal fun HistoryEventCard(event: TaskHistoryEvent) {
    val icon = when (event.type) {
        TaskHistoryEventType.VOTE_AFFINITY -> Icons.Default.Favorite
        TaskHistoryEventType.VOTE_EFFORT -> Icons.Default.Timer
        TaskHistoryEventType.VOTE_FREQUENCY -> Icons.Default.Schedule
        TaskHistoryEventType.TASK_UPDATE -> Icons.Default.Edit
        TaskHistoryEventType.TASK_CREATE -> Icons.Default.Add
        TaskHistoryEventType.MEMBER_LEFT -> Icons.AutoMirrored.Filled.Logout
        TaskHistoryEventType.UNKNOWN -> Icons.Default.History
    }
    val label = formatHistoryEvent(event)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (event.at != null) {
                    Text(
                        text = formatDateTime(event.at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formate un evenement d'historique en libelle localisé.
 *
 * - VOTE_EFFORT : les points sont convertis en minutes (1pt = 5min)
 * - VOTE_FREQUENCY / VOTE_AFFINITY : valeurs affichees telles quelles
 * - TASK_UPDATE : liste des champs modifies (titre, recurrence, ...)
 */
@Composable
private fun formatHistoryEvent(event: TaskHistoryEvent): String {
    val actor = event.actorName.ifBlank { stringResource(R.string.task_history_actor_fallback) }
    return when (event.type) {
        TaskHistoryEventType.VOTE_FREQUENCY -> {
            val value = event.voteValue ?: return stringResource(R.string.task_history_vote_freq_no_value_format, actor)
            val consensus = event.consensusAfter
            if (consensus != null) {
                stringResource(R.string.task_history_vote_freq_consensus_format, actor, value, consensus)
            } else {
                stringResource(R.string.task_history_vote_freq_format, actor, value)
            }
        }
        TaskHistoryEventType.VOTE_AFFINITY -> {
            val value = event.voteValue ?: return stringResource(R.string.task_history_vote_aff_no_value_format, actor)
            val consensus = event.consensusAfter
            if (consensus != null) {
                stringResource(R.string.task_history_vote_aff_consensus_format, actor, value, consensus)
            } else {
                stringResource(R.string.task_history_vote_aff_format, actor, value)
            }
        }
        TaskHistoryEventType.VOTE_EFFORT -> {
            val points = event.voteValue ?: return stringResource(R.string.task_history_vote_eff_no_value_format, actor)
            val minutes = points * 5
            val consensusMin = event.consensusAfter?.let { it * 5 }
            val voteLabel = formatMinutes(minutes)
            if (consensusMin != null) {
                stringResource(
                    R.string.task_history_vote_eff_consensus_format, actor, voteLabel, formatMinutes(consensusMin),
                )
            } else {
                stringResource(R.string.task_history_vote_eff_format, actor, voteLabel)
            }
        }
        TaskHistoryEventType.TASK_UPDATE -> {
            val fields = event.fieldsChanged.mapNotNull { localizedFieldName(it) }
            when {
                fields.isEmpty() -> stringResource(R.string.task_history_update_unknown_format, actor)
                fields.size == 1 -> stringResource(R.string.task_history_update_one_format, actor, fields[0])
                else -> stringResource(R.string.task_history_update_many_format, actor, fields.size)
            }
        }
        TaskHistoryEventType.TASK_CREATE -> stringResource(R.string.task_history_create_format, actor)
        TaskHistoryEventType.MEMBER_LEFT -> stringResource(R.string.task_history_member_left_format, actor)
        TaskHistoryEventType.UNKNOWN -> stringResource(R.string.task_history_update_unknown_format, actor)
    }
}

@Composable
private fun localizedFieldName(key: String): String? = taskFieldLabelRes(key)?.let { stringResource(it) }

package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.EffortVotesState
import kotlin.math.roundToInt

/**
 * Mini-dialog dedie au vote d'effort (v0.28). Parallele de
 * FrequencyVoteDialog mais sur l'effort (estimation du temps).
 *
 * Slider 1pt (5min) -> 12pt (1h). Le consensus du foyer = moyenne
 * arrondie des votes ; sert au calcul des points de score.
 *
 * Le dialog est ouvert :
 *   - Depuis le bandeau / "+ d'info" du detail tache (mode vote spontane).
 *   - Depuis un prompt VOTE_EFFORT (mode "X t'a demande"). Dans ce
 *     dernier cas, `requesterName` non-null affiche un bandeau d'intro.
 */
@Composable
fun EffortVoteDialog(
    taskTitle: String,
    state: EffortVotesState,
    requesterName: String? = null,
    onSubmit: (points: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = state.myPoints ?: state.consensusPoints ?: 3
    var pointsValue by remember(state.taskId) { mutableFloatStateOf(initial.toFloat().coerceIn(1f, 12f)) }
    val pointsInt = pointsValue.roundToInt().coerceIn(1, 12)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon vote sur l'effort") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (requesterName != null) {
                    Text(
                        text = "$requesterName t'a demande de revoter sur cette tache.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Selon toi, combien de temps prend cette tache ?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    text = effortPointsToTime(pointsInt),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Slider(
                    value = pointsValue,
                    onValueChange = { pointsValue = it },
                    valueRange = 1f..12f,
                    steps = 10,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("5min", style = MaterialTheme.typography.labelSmall)
                    Text("1h", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))

                if (state.votesCount > 0 && state.consensusPoints != null) {
                    val s = if (state.votesCount > 1) "s" else ""
                    Text(
                        text = "Consensus actuel : ${effortPointsToTime(state.consensusPoints)} (${state.votesCount} vote$s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.others.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    state.others.forEach { other ->
                        Text(
                            text = "${other.displayName} : ${effortPointsToTime(other.points)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.myPoints == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tu n'as pas encore vote — la valeur ci-dessus deviendra ton vote.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(pointsInt) }) { Text("Voter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/** Convertit des points (1pt=5min) en label humain "Xmin" ou "XhYY". */
internal fun effortPointsToTime(points: Int): String {
    val minutes = points * 5
    return when {
        minutes < 60 -> "${minutes}min"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h${"%02d".format(minutes % 60)}"
    }
}

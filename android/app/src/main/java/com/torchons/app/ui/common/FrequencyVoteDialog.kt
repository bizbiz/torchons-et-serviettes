package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.FrequencyVotesState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mini-dialog dedie au vote de frequence d'une tache, ouvert depuis la
 * fiche d'edition (champ every_n_days grise).
 *
 * Sous-ensemble de RatingDialog : ne montre QUE le slider de frequence,
 * sans affinite ni effort. Affiche le consensus actuel + la liste des
 * autres voteurs pour transparence (decision Q7 du grilling).
 *
 * @param state etat des votes recupere via GET /tasks/{id}/frequency-votes.
 *   Si state.consensusEveryNDays est null (rare), on initialise le slider
 *   a 7j par defaut.
 * @param onSubmit callback avec la nouvelle valeur (every_n_days) choisie.
 *   Caller appelle PUT /tasks/{id}/frequency puis refetch l'etat.
 */
@Composable
fun FrequencyVoteDialog(
    taskTitle: String,
    state: FrequencyVotesState,
    onSubmit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val consensus = state.consensusEveryNDays ?: 7
    val initial = state.myVote ?: consensus

    // Range adaptative 30%-300% du consensus pour rester pertinent (proposer
    // 365j a quelqu'un qui vote sur 5j n'a pas de sens ; et le curseur sur
    // une plage 1-365 etait visuellement colle a gauche pour les petites
    // valeurs). Etendue pour inclure myVote si l'user avait deja vote tres
    // differemment du consensus actuel. Clamp absolu [1, 365] = contrat
    // backend (FrequencyUpdate.every_n_days: Field(ge=1, le=365)).
    val baseMin = max(1, (consensus * 0.3f).roundToInt())
    val baseMax = max(baseMin + 1, min(365, (consensus * 3f).roundToInt()))
    val sliderMin = min(baseMin, initial).coerceAtLeast(1)
    val sliderMax = max(baseMax, initial).coerceAtMost(365).coerceAtLeast(sliderMin + 1)
    val sliderSteps = (sliderMax - sliderMin - 1).coerceAtLeast(0)

    var daysValue by remember(state.taskId) { mutableFloatStateOf(initial.toFloat()) }

    val daysLabel = if (daysValue.toInt() == 1) "1 jour" else "${daysValue.toInt()} jours"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voter pour la cadence") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Tous les $daysLabel", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = daysValue,
                    onValueChange = { daysValue = it },
                    valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
                    steps = sliderSteps,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Consensus actuel + nombre de votes
                if (state.votesCount > 0 && state.consensusEveryNDays != null) {
                    val s = if (state.votesCount > 1) "s" else ""
                    Text(
                        text = "Consensus actuel : ${state.consensusEveryNDays} j (${state.votesCount} vote$s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Liste des votes des autres membres
                if (state.others.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    state.others.forEach { other ->
                        Text(
                            text = "${other.displayName} : ${other.everyNDays} j",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Indication si l'user n'a pas encore vote
                if (state.myVote == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tu n'as pas encore vote — la valeur ci-dessus deviendra ton vote.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(daysValue.toInt()) }) {
                Text("Voter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

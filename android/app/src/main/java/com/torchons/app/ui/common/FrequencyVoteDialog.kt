package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit
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
 * Le slider stocke toujours la valeur en jours (canonique). Le toggle
 * d'unite (Jours / Semaines / Mois) sert a (1) afficher le label de
 * facon humaine ("Tous les 3 mois 2 j") et (2) snapper le slider sur
 * des multiples de l'unite — ainsi en mode MOIS, le curseur ne s'arrete
 * que sur 30 j, 60 j, 90 j... Pratique quand on veut voter "3 mois".
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
    var unit by remember(state.taskId) { mutableStateOf(DurationUnit.bestFor(initial)) }

    val daysInt = daysValue.toInt()
    val daysLabel = DurationFormat.formatDaysHuman(daysInt, long = true)

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

                // Toggle d'unite — disable un cran si la fenetre du slider
                // ne le contient pas (ex: pour un consensus a 5 j, MOIS
                // serait inutilisable car sliderMax < 30).
                val units = DurationUnit.values().toList()
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    units.forEachIndexed { idx, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = {
                                unit = u
                                // Snap la valeur courante sur la nouvelle unite.
                                val snapped = u.toDays(u.fromDays(daysInt))
                                    .coerceIn(sliderMin, sliderMax)
                                daysValue = snapped.toFloat()
                            },
                            shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                            enabled = sliderMax >= u.days,
                        ) { Text(u.label) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text("Tous les $daysLabel", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = daysValue,
                    onValueChange = { v ->
                        // Snap sur multiples de unit.days dans [sliderMin, sliderMax].
                        val raw = v.roundToInt()
                        val snapped = if (unit == DurationUnit.JOURS) raw
                        else unit.toDays(unit.fromDays(raw))
                        daysValue = snapped.coerceIn(sliderMin, sliderMax).toFloat()
                    },
                    valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
                    steps = sliderSteps,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Consensus actuel + nombre de votes
                if (state.votesCount > 0 && state.consensusEveryNDays != null) {
                    val s = if (state.votesCount > 1) "s" else ""
                    val consensusHuman = DurationFormat.formatDaysHuman(state.consensusEveryNDays)
                    Text(
                        text = "Consensus actuel : $consensusHuman (${state.votesCount} vote$s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Liste des votes des autres membres
                if (state.others.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    state.others.forEach { other ->
                        val otherHuman = DurationFormat.formatDaysHuman(other.everyNDays)
                        Text(
                            text = "${other.displayName} : $otherHuman",
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

package com.torchons.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MONTH_KEYS = (1..12).map { it.toString() }
private val MONTH_LABELS = listOf(
    "Janvier", "Fevrier", "Mars", "Avril", "Mai", "Juin",
    "Juillet", "Aout", "Septembre", "Octobre", "Novembre", "Decembre",
)

/** True si le dict de vote a des valeurs differentes selon le mois. */
private fun isVariableDpm(dpm: Map<String, Int>?): Boolean {
    if (dpm.isNullOrEmpty()) return false
    val values = dpm.values
    val first = values.first()
    return values.any { it != first }
}

private enum class VoteMode { SIMPLE, VARIABLE }

/**
 * Mini-dialog dedie au vote de frequence (v0.27 — modes Reguliere / Selon
 * le mois). Toggle en haut bascule entre les 2 modes :
 *
 * - **Reguliere** : slider unique avec switch d'unite Jours/Semaines/Mois.
 *   La valeur est ecrite 12 fois identiques (forme canonique 12-mois).
 * - **Selon le mois** : 12 sliders compactes, un par mois. Permet de
 *   refleter une cadence saisonniere (5j ete, 20j hiver, etc.).
 *
 * Le consensus du foyer est affiche sous le slider/grille avec la liste
 * des autres voteurs (resume textuel "tous les Xj" ou "Variable").
 */
@Composable
fun FrequencyVoteDialog(
    taskTitle: String,
    state: FrequencyVotesState,
    onSubmit: (FrequencyRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val consensusInt = state.consensusAverageDays ?: 7
    val initialIsVariable = isVariableDpm(state.myDaysPerMonth)
    var voteMode by remember(state.taskId) {
        mutableStateOf(if (initialIsVariable) VoteMode.VARIABLE else VoteMode.SIMPLE)
    }

    // Etat partage entre l'enfant (slider/grille) et le bouton Voter du
    // parent : le bouton Voter clique declenche le submit du mode actif.
    // On stocke le getter de la valeur courante dans une `mutableStateOf`
    // pour que le bouton Voter le lise au moment du clic.
    val pendingSubmit = remember(state.taskId) {
        mutableStateOf<(() -> FrequencyRequest)?>(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon vote sur la frequence") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                Spacer(Modifier.height(12.dp))

                // Toggle Reguliere / Selon le mois
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = voteMode == VoteMode.SIMPLE,
                        onClick = { voteMode = VoteMode.SIMPLE },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Reguliere") }
                    SegmentedButton(
                        selected = voteMode == VoteMode.VARIABLE,
                        onClick = { voteMode = VoteMode.VARIABLE },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Selon le mois") }
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Selon toi, tous les combien faut-il faire cette tache ?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                when (voteMode) {
                    VoteMode.SIMPLE -> SimpleModeBody(
                        state = state,
                        consensusInt = consensusInt,
                        registerGetter = { getter ->
                            pendingSubmit.value = { FrequencyRequest(everyNDays = getter()) }
                        },
                    )
                    VoteMode.VARIABLE -> VariableModeBody(
                        state = state,
                        consensusInt = consensusInt,
                        registerGetter = { getter ->
                            pendingSubmit.value = { FrequencyRequest(daysPerMonth = getter()) }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                pendingSubmit.value?.invoke()?.let(onSubmit)
            }) { Text("Voter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun SimpleModeBody(
    state: FrequencyVotesState,
    consensusInt: Int,
    registerGetter: (() -> Int) -> Unit,
) {
    // Initiale = vote actuel uniforme si dispo, sinon consensus.
    val myUniform = state.myDaysPerMonth?.values?.takeIf {
        it.isNotEmpty() && it.all { v -> v == it.first() }
    }?.first()
    val initial = myUniform ?: consensusInt

    val baseMin = max(1, (consensusInt * 0.3f).roundToInt())
    val baseMax = max(baseMin + 1, min(365, (consensusInt * 3f).roundToInt()))
    val sliderMin = min(baseMin, initial).coerceAtLeast(1)
    val sliderMax = max(baseMax, initial).coerceAtMost(365).coerceAtLeast(sliderMin + 1)
    val sliderSteps = (sliderMax - sliderMin - 1).coerceAtLeast(0)

    var daysValue by remember(state.taskId) { mutableFloatStateOf(initial.toFloat()) }
    var unit by remember(state.taskId) { mutableStateOf(DurationUnit.bestFor(initial)) }

    val daysInt = daysValue.toInt()
    val daysLabel = DurationFormat.formatDaysHuman(daysInt, long = true)

    // Re-enregistre le getter a chaque recomposition (capture daysValue a jour).
    registerGetter { daysValue.toInt() }

    val units = DurationUnit.values().toList()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        units.forEachIndexed { idx, u ->
            SegmentedButton(
                selected = unit == u,
                onClick = {
                    unit = u
                    val snapped = u.toDays(u.fromDays(daysInt))
                        .coerceIn(sliderMin, sliderMax)
                    daysValue = snapped.toFloat()
                },
                shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                enabled = sliderMax >= u.days,
            ) { Text(u.label) }
        }
    }
    Spacer(Modifier.height(8.dp))

    Text("Tous les $daysLabel", style = MaterialTheme.typography.titleMedium)
    Slider(
        value = daysValue,
        onValueChange = { v ->
            val raw = v.roundToInt()
            val snapped = if (unit == DurationUnit.JOURS) raw
            else unit.toDays(unit.fromDays(raw))
            daysValue = snapped.coerceIn(sliderMin, sliderMax).toFloat()
        },
        valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
        steps = sliderSteps,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    ConsensusFooter(state)
}

@Composable
private fun VariableModeBody(
    state: FrequencyVotesState,
    consensusInt: Int,
    registerGetter: (() -> Map<String, Int>) -> Unit,
) {
    // Init : copie du vote courant, sinon consensus rempli sur 12 mois.
    val perMonth: SnapshotStateMap<String, Int> = remember(state.taskId) {
        mutableStateMapOf<String, Int>().apply {
            for (m in MONTH_KEYS) {
                val v = state.myDaysPerMonth?.get(m)
                    ?: state.consensusRule?.daysPerMonth?.get(m)
                    ?: consensusInt
                this[m] = v.coerceIn(1, 365)
            }
        }
    }

    registerGetter { perMonth.toMap() }

    Text(
        text = "Cadence par mois (jours entre 2 fois)",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    MONTH_KEYS.forEachIndexed { idx, m ->
        val value = perMonth[m] ?: consensusInt
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = MONTH_LABELS[idx],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(80.dp),
            )
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${value}j",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { v ->
                    perMonth[m] = v.roundToInt().coerceIn(1, 365)
                },
                valueRange = 1f..90f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    ConsensusFooter(state)
}

@Composable
private fun ConsensusFooter(state: FrequencyVotesState) {
    val rule = state.consensusRule
    if (state.votesCount > 0 && rule != null) {
        val s = if (state.votesCount > 1) "s" else ""
        val human = when (rule.type) {
            "interval" -> rule.everyNDays?.let {
                "tous les ${DurationFormat.formatDaysHuman(it)}"
            } ?: "?"
            "monthly_variable" -> "variable selon le mois"
            else -> "?"
        }
        Text(
            text = "Consensus actuel : $human (${state.votesCount} vote$s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.others.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        state.others.forEach { other ->
            val isVar = isVariableDpm(other.daysPerMonth)
            val label = if (isVar) "variable selon le mois"
            else other.daysPerMonth.values.firstOrNull()?.let {
                "tous les ${DurationFormat.formatDaysHuman(it)}"
            } ?: "?"
            Text(
                text = "${other.displayName} : $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.myDaysPerMonth == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tu n'as pas encore vote — la valeur ci-dessus deviendra ton vote.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

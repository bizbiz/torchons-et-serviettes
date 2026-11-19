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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MONTH_KEYS = (1..12).map { it.toString() }

/** Liste des labels de mois complets, localises via stringResource. */
@Composable
private fun monthFullLabels(): List<String> = listOf(
    stringResource(R.string.month_full_jan),
    stringResource(R.string.month_full_feb),
    stringResource(R.string.month_full_mar),
    stringResource(R.string.month_full_apr),
    stringResource(R.string.month_full_may),
    stringResource(R.string.month_full_jun),
    stringResource(R.string.month_full_jul),
    stringResource(R.string.month_full_aug),
    stringResource(R.string.month_full_sep),
    stringResource(R.string.month_full_oct),
    stringResource(R.string.month_full_nov),
    stringResource(R.string.month_full_dec),
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
    header: VoteTaskHeader,
    state: FrequencyVotesState,
    onSubmit: (FrequencyRequest) -> Unit,
    onDismiss: () -> Unit,
    requesterName: String? = null,
    /** Replaces the requester banner (« souvent en retard » re-vote chain). */
    introText: String? = null,
) {
    val intro = introText
        ?: requesterName?.let { stringResource(R.string.frequency_vote_requester_format, it) }
    val consensusInt = state.consensusAverageDays ?: 7
    val initialIsVariable = isVariableDpm(state.myDaysPerMonth)
    var voteMode by remember(state.taskId) {
        mutableStateOf(if (initialIsVariable) VoteMode.VARIABLE else VoteMode.SIMPLE)
    }

    val pendingSubmit = remember(state.taskId) {
        mutableStateOf<(() -> FrequencyRequest)?>(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.frequency_vote_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (intro != null) {
                    Text(
                        text = intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                TaskContextsLine(header.contextsLine)
                Spacer(Modifier.height(12.dp))

                // Toggle Reguliere / Selon le mois
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = voteMode == VoteMode.SIMPLE,
                        onClick = { voteMode = VoteMode.SIMPLE },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.frequency_vote_mode_simple)) }
                    SegmentedButton(
                        selected = voteMode == VoteMode.VARIABLE,
                        onClick = { voteMode = VoteMode.VARIABLE },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.frequency_vote_mode_variable)) }
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.frequency_vote_prompt),
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
            }) { Text(stringResource(R.string.frequency_vote_button_vote)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SimpleModeBody(
    state: FrequencyVotesState,
    consensusInt: Int,
    registerGetter: (() -> Int) -> Unit,
) {
    val myUniform = state.myDaysPerMonth?.values?.takeIf {
        it.isNotEmpty() && it.all { v -> v == it.first() }
    }?.first()
    val initial = myUniform ?: consensusInt

    var daysValue by remember(state.taskId) { mutableIntStateOf(initial) }

    val res = LocalContext.current.resources
    val daysLabel = DurationFormat.formatDaysHuman(res, daysValue, long = true)

    registerGetter { daysValue }

    FrequencyScaleSlider(
        days = daysValue,
        onDaysChange = { daysValue = it },
        resetKey = state.taskId,
    ) {
        Text(
            stringResource(R.string.frequency_vote_every_format, daysLabel),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(8.dp))
    ConsensusFooter(state)
}

@Composable
private fun VariableModeBody(
    state: FrequencyVotesState,
    consensusInt: Int,
    registerGetter: (() -> Map<String, Int>) -> Unit,
) {
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

    val monthLabels = monthFullLabels()

    Text(
        text = stringResource(R.string.frequency_vote_variable_header),
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
                text = monthLabels[idx],
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
                    text = stringResource(R.string.frequency_vote_value_short_j_format, value),
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
    val res = LocalContext.current.resources
    val rule = state.consensusRule
    if (state.votesCount > 0 && rule != null) {
        val human = when (rule.type) {
            "interval" -> rule.everyNDays?.let {
                stringResource(R.string.frequency_info_interval_format, DurationFormat.formatDaysHuman(res, it))
            } ?: stringResource(R.string.frequency_info_unknown)
            "monthly_variable" -> stringResource(R.string.frequency_info_variable_label)
            else -> stringResource(R.string.frequency_info_unknown)
        }
        Text(
            text = pluralStringResource(
                R.plurals.frequency_vote_consensus_format,
                state.votesCount,
                human,
                state.votesCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.others.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        state.others.forEach { other ->
            val isVar = isVariableDpm(other.daysPerMonth)
            val label = if (isVar) stringResource(R.string.frequency_info_variable_label)
            else other.daysPerMonth.values.firstOrNull()?.let {
                stringResource(R.string.frequency_info_interval_format, DurationFormat.formatDaysHuman(res, it))
            } ?: stringResource(R.string.frequency_info_unknown)
            Text(
                text = stringResource(R.string.frequency_vote_other_format, other.displayName, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.myDaysPerMonth == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.frequency_vote_no_vote_yet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

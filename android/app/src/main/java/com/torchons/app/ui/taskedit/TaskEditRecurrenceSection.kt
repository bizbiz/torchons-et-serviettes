package com.torchons.app.ui.taskedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.recurrenceSentence
import com.torchons.app.ui.common.submitKeyboardActions
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit

/** Recurrence type picker and the fields of each type, tolerance included. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskEditRecurrenceSection(
    uiState: TaskEditUiState,
    viewModel: TaskEditViewModel,
) {
    // Recurrence — editable en creation ET en edition. Le vote individuel
    // (RatingDialog depuis TaskDetail / home) reste une autre voie pour
    // ajuster la cadence d'un interval, mais changer de type (ex. passer
    // d'interval a uncertain) se fait ici.
    var recurrenceExpanded by remember { mutableStateOf(false) }
    // v0.27 — libelles plus lisibles (UX tests internes) :
    // - interval -> Reguliere
    // - monthly_variable -> Selon le mois
    // - uncertain -> Irreguliere
    // - none -> Une seule fois
    val recurrenceLabel = when (uiState.recurrenceType) {
        "interval" -> stringResource(R.string.task_edit_recurrence_interval)
        "monthly_variable" -> stringResource(R.string.task_edit_recurrence_monthly)
        "uncertain" -> stringResource(R.string.task_edit_recurrence_uncertain)
        "calendar" -> stringResource(R.string.task_edit_recurrence_calendar)
        "anniversary" -> stringResource(R.string.task_edit_recurrence_anniversary)
        "none" -> stringResource(R.string.task_edit_recurrence_none)
        else -> uiState.recurrenceType
    }

    ExposedDropdownMenuBox(
        expanded = recurrenceExpanded,
        onExpandedChange = { recurrenceExpanded = it }
    ) {
        OutlinedTextField(
            value = recurrenceLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.task_edit_field_recurrence)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = recurrenceExpanded,
            onDismissRequest = { recurrenceExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_interval)) },
                onClick = {
                    viewModel.updateRecurrenceType("interval")
                    recurrenceExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_monthly)) },
                onClick = {
                    viewModel.updateRecurrenceType("monthly_variable")
                    recurrenceExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_uncertain)) },
                onClick = {
                    viewModel.updateRecurrenceType("uncertain")
                    recurrenceExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_calendar)) },
                onClick = {
                    viewModel.updateRecurrenceType("calendar")
                    recurrenceExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_anniversary)) },
                onClick = {
                    viewModel.updateRecurrenceType("anniversary")
                    recurrenceExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_edit_recurrence_none)) },
                onClick = {
                    viewModel.updateRecurrenceType("none")
                    recurrenceExpanded = false
                }
            )
        }
    }

    // Type `calendar` : les mois d'echeance, le jour, la tolerance.
    // Pas de vote de frequence ici — une date fixe n'est pas une
    // cadence a estimer, c'est un fait du calendrier.
    if (uiState.recurrenceType == "calendar") {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.task_edit_calendar_months_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        val moisCourts = stringArrayResource(R.array.month_names_short)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..12).forEach { m ->
                FilterChip(
                    selected = m in uiState.recurrenceMonths,
                    onClick = { viewModel.toggleRecurrenceMonth(m) },
                    label = { Text(moisCourts.getOrElse(m - 1) { "?" }) },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.recurrenceDay.toString(),
            onValueChange = { it.toIntOrNull()?.let(viewModel::updateRecurrenceDay) },
            label = { Text(stringResource(R.string.task_edit_calendar_day_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            keyboardActions = submitKeyboardActions(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.recurrenceGraceDays.toString(),
            onValueChange = { it.toIntOrNull()?.let(viewModel::updateRecurrenceGraceDays) },
            label = { Text(stringResource(R.string.task_edit_calendar_grace_label)) },
            supportingText = {
                Text(stringResource(R.string.task_edit_calendar_grace_help))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            keyboardActions = submitKeyboardActions(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // `anniversary`: only the period. The anchor is the first recorded
    // completion, not a field: no vote either, the date is a fact.
    if (uiState.recurrenceType == "anniversary") {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.anniversaryAmount.toString(),
                onValueChange = { it.toIntOrNull()?.let(viewModel::updateAnniversaryAmount) },
                label = { Text(stringResource(R.string.task_edit_anniversary_period_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                keyboardActions = submitKeyboardActions(),
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = uiState.anniversaryInYears,
                onClick = { viewModel.updateAnniversaryInYears(true) },
                label = { Text(stringResource(R.string.task_edit_anniversary_unit_years)) },
            )
            FilterChip(
                selected = !uiState.anniversaryInYears,
                onClick = { viewModel.updateAnniversaryInYears(false) },
                label = { Text(stringResource(R.string.task_edit_anniversary_unit_months)) },
            )
        }
        Text(
            text = stringResource(R.string.task_edit_anniversary_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // La phrase : elle explique le COMPORTEMENT (l'echeance glisse-t-elle
    // ou non ?), ce qu'aucun libelle de menu ne dit.
    uiState.previewRecurrenceRule()?.let { previsu ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = recurrenceSentence(previsu),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Nombre de jours si interval. Si la tache a deja >=1 vote de
    // frequence, le champ est grise et seul le mini-dialog "Modifier
    // via le vote" peut le modifier (decision Q1+Q5 du grilling :
    // every_n_days est pilote par les votes en mode interval).
    // L'utilisateur peut basculer l'unite de saisie entre Jours,
    // Semaines et Mois (1 sem = 7 j, 1 mois = 30 j) via le segmented
    // button. Le breakdown smart sous le champ ("3 mois 2 j") aide
    // a se reperer quand la valeur ne tombe pas pile sur l'unite.
    if (uiState.recurrenceType == "interval") {
        val votes = uiState.frequencyVotes
        val isVoteLocked = votes != null && votes.votesCount >= 1
        val unit = uiState.recurrenceUnit
        val canonicalDays = uiState.recurrenceFieldText.toIntOrNull()
            ?.let { unit.toDays(it) } ?: 7

        val res = LocalContext.current.resources

        // Toggle d'unite [Jours | Semaines | Mois]
        val units = DurationUnit.values().toList()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            units.forEachIndexed { idx, u ->
                SegmentedButton(
                    selected = unit == u,
                    onClick = { viewModel.updateRecurrenceUnit(u) },
                    shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                    enabled = !isVoteLocked,
                ) { Text(stringResource(u.labelRes)) }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = uiState.recurrenceFieldText,
            onValueChange = { viewModel.updateRecurrenceFieldText(it) },
            label = {
                Text(
                    stringResource(
                        R.string.task_edit_every_n_format,
                        stringResource(unit.labelRes).lowercase(),
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardActions = submitKeyboardActions(),
            enabled = !isVoteLocked,
        )
        // Breakdown smart sous le champ — seulement si la valeur
        // depasse l'unite (eviter "1 mois ≈ 1 mois").
        if (canonicalDays >= 7) {
            val human = DurationFormat.formatDaysHuman(res, canonicalDays, long = true)
            Text(
                text = stringResource(R.string.task_edit_breakdown_format, human, canonicalDays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (isVoteLocked) {
            val consensusDays = votes!!.consensusEveryNDays ?: 0
            val consensusHuman = if (consensusDays > 0)
                DurationFormat.formatDaysHuman(res, consensusDays)
            else stringResource(R.string.task_edit_consensus_unknown)
            Text(
                text = pluralStringResource(
                    R.plurals.task_edit_consensus_format,
                    votes.votesCount,
                    votes.votesCount,
                    consensusHuman,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            if (votes.canVote) {
                OutlinedButton(
                    onClick = { viewModel.openFrequencyVoteDialog() },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.task_edit_edit_vote_button))
                }
            }
        }
    }

    // Tableau 12 mois si monthly_variable
    if (uiState.recurrenceType == "monthly_variable") {
        val monthNames = listOf(
            stringResource(R.string.task_edit_month_jan),
            stringResource(R.string.task_edit_month_feb),
            stringResource(R.string.task_edit_month_mar),
            stringResource(R.string.task_edit_month_apr),
            stringResource(R.string.task_edit_month_may),
            stringResource(R.string.task_edit_month_jun),
            stringResource(R.string.task_edit_month_jul),
            stringResource(R.string.task_edit_month_aug),
            stringResource(R.string.task_edit_month_sep),
            stringResource(R.string.task_edit_month_oct),
            stringResource(R.string.task_edit_month_nov),
            stringResource(R.string.task_edit_month_dec),
        )
        Text(
            text = stringResource(R.string.task_edit_monthly_header),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 2 colonnes de 6 mois
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..6).forEach { m ->
                    OutlinedTextField(
                        value = uiState.daysPerMonth[m.toString()] ?: "",
                        onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                        label = { Text(monthNames[m - 1]) },
                        singleLine = true,
                        keyboardActions = submitKeyboardActions()
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (7..12).forEach { m ->
                    OutlinedTextField(
                        value = uiState.daysPerMonth[m.toString()] ?: "",
                        onValueChange = { viewModel.updateDaysPerMonth(m.toString(), it) },
                        label = { Text(monthNames[m - 1]) },
                        singleLine = true,
                        keyboardActions = submitKeyboardActions()
                    )
                }
            }
        }
    }

    // Tolerance au retard : uniquement pour les recurrences a cadence
    // definie (interval, monthly_variable). Cachee pour 'none' (ponctuelle)
    // et 'uncertain' (sans cadence) qui n'utilisent pas le tier tolerance
    // dans le selecteur d'urgence du home.
    if (uiState.recurrenceType == "interval" || uiState.recurrenceType == "monthly_variable") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.task_edit_field_tolerance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            InfoTooltip(
                title = stringResource(R.string.task_detail_tolerance_help_title),
                description = com.torchons.app.ui.common.toleranceInfoText(),
            )
        }
        val tolerances = listOf(
            "STRICT" to stringResource(R.string.task_tolerance_strict),
            "NORMAL" to stringResource(R.string.task_tolerance_normal),
            "SOUPLE" to stringResource(R.string.task_tolerance_souple),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tolerances.forEachIndexed { idx, (value, label) ->
                SegmentedButton(
                    selected = uiState.tolerance == value,
                    onClick = { viewModel.updateTolerance(value) },
                    shape = SegmentedButtonDefaults.itemShape(idx, tolerances.size),
                ) { Text(label) }
            }
        }
    }
}

package com.torchons.app.ui.common

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.CompletionPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * « Date + Qui » (docs/plans/a-faire-aujourdhui.md): ONE dialog with check boxes, which replaced
 * the « Déjà fait » (radio) and « Fait ensemble » modes on 2026-11-18. At opening only I am checked
 * (on prod, 162 backdated completions out of 179 were done by one person), unless the caller
 * passes [initialParticipantIds] (the task page's « Fait ensemble »). The confirm button names
 * the selection and the day (WhoWhenLabel.kt): « Toi aujourd'hui », « Bob & Toi hier »,
 * « 4 personnes mardi ». One participant is a single completion, several a collective one:
 * the caller tells them apart by the list size.
 *
 * A solo task (one assignee) offers only its assignee: the calendar opens at once.
 * The date is kept as UTC millis and returned as ISO 8601 at noon UTC (T12:00:00Z).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletionDialog(
    taskTitle: String,
    allMembers: List<Pair<String, String>>,
    assignedMemberIds: Set<String>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onConfirm: (participantIds: List<String>, isoDate: String, preview: CompletionPreview?) -> Unit,
    /**
     * Fournisseur de preview des points (bonus/malus). null = offline ou preview
     * indisponible — on affiche un fallback "≈ X pts". Appele a chaque changement
     * de date ou de nombre de participants.
     */
    previewProvider: (suspend (isoDate: String, numParticipants: Int) -> CompletionPreview?)? = null,
    /** v0.32 : si false, masque la ligne "Tu gagneras X pts". */
    gamificationVisible: Boolean = true,
    /** Checked at opening; null = me alone. */
    initialParticipantIds: Set<String>? = null,
) {
    var selectedDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePickerSub by remember { mutableStateOf(false) }

    // A solo task (one assignee) can only have been done by that member (Quentin, 2026-11-12).
    val visibleMembers =
        if (assignedMemberIds.size == 1) allMembers.filter { it.first in assignedMemberIds } else allMembers
    val meIfListed = visibleMembers.map { it.first }.filter { it == currentUserId }.toSet()
    var selectedIds by remember { mutableStateOf(initialParticipantIds ?: meIfListed) }

    val numParticipants = selectedIds.size.coerceAtLeast(1)
    val isoDateForPreview = remember(selectedDateMillis) { millisToIsoNoonUtc(selectedDateMillis) }
    var preview by remember { mutableStateOf<CompletionPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    if (previewProvider != null) {
        LaunchedEffect(isoDateForPreview, numParticipants) {
            previewFailed = false
            preview = try {
                previewProvider(isoDateForPreview, numParticipants)
            } catch (_: Exception) {
                previewFailed = true
                null
            }
            if (preview == null) previewFailed = true
        }
    }

    val res = LocalContext.current.resources

    // A solo task leaves only « when » to choose: straight to the calendar.
    if (assignedMemberIds.size == 1 && visibleMembers.size == 1) {
        SoloCompletionDatePicker(taskTitle, visibleMembers.first().first, onDismiss, onConfirm, previewProvider)
        return
    }

    if (showDatePickerSub) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePickerSub = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { selectedDateMillis = it }
                    showDatePickerSub = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerSub = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) { DatePicker(state = dpState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.completion_title_who_when),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedButton(onClick = { showDatePickerSub = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.width(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(formatDateLocalized(res, selectedDateMillis))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.completion_picker_who_single), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                visibleMembers.forEach { (id, name) ->
                    val toggle = { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = id in selectedIds, onCheckedChange = { toggle() })
                        Text(name)
                    }
                }
                if (previewProvider != null && gamificationVisible) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val checked = visibleMembers.map { it.first }.filter { it in selectedIds }
                    PreviewPointsLine(
                        preview = preview,
                        failed = previewFailed,
                        participants = numParticipants,
                        earnText = { pts -> pointsEarnLabel(res, checked, currentUserId, visibleMembers.toMap(), pts) },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                // Members in list order, so the server receives a stable participant list.
                val chosen = visibleMembers.map { it.first }.filter { it in selectedIds }
                Button(
                    enabled = chosen.isNotEmpty(),
                    onClick = { onConfirm(chosen, millisToIsoNoonUtc(selectedDateMillis), preview) },
                ) {
                    Text(
                        whoWhenLabel(
                            res, chosen, currentUserId, visibleMembers.toMap(),
                            Instant.ofEpochMilli(selectedDateMillis).atOffset(ZoneOffset.UTC).toLocalDate(),
                            LocalDate.now(ZoneOffset.UTC), // same reading of the day as formatDateLocalized
                        )
                    )
                }
            }
        },
        dismissButton = {},
    )
}

/**
 * « Déjà fait » on a solo task (one assignee): nobody to choose, so no « Qui a fait ? » dialog
 * (Quentin, 2026-11-12) — the calendar opens at once, past dates only, and « Valider » completes.
 * The points preview is read for the chosen date at that moment, for the celebration, but never
 * waited on for more than 1.5 s (offline it would time out much later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoloCompletionDatePicker(
    taskTitle: String,
    memberId: String,
    onDismiss: () -> Unit,
    onConfirm: (participantIds: List<String>, isoDate: String, preview: CompletionPreview?) -> Unit,
    previewProvider: (suspend (isoDate: String, numParticipants: Int) -> CompletionPreview?)?,
) {
    val now = remember { System.currentTimeMillis() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = now,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
        },
    )
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = !sent, onClick = {
                sent = true
                val iso = millisToIsoNoonUtc(state.selectedDateMillis ?: now)
                scope.launch {
                    val preview = withTimeoutOrNull(1500) {
                        runCatching { previewProvider?.invoke(iso, 1) }.getOrNull()
                    }
                    onConfirm(listOf(memberId), iso, preview)
                }
            }) { Text(stringResource(R.string.common_validate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        DatePicker(
            state = state,
            title = {
                Column(modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.completion_title_single),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = taskTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/**
 * Formate la date selon la locale courante (ex: "15 avril 2026" en FR,
 * "April 15, 2026" en EN), avec raccourcis "Aujourd'hui"/"Hier" pour
 * les 2 dates les plus communes.
 */
private fun formatDateLocalized(res: Resources, millis: Long): String {
    val date = Instant.ofEpochMilli(millis)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    val today = LocalDate.now(ZoneOffset.UTC)
    val yesterday = today.minusDays(1)
    return when (date) {
        today -> res.getString(R.string.completion_date_today)
        yesterday -> res.getString(R.string.completion_date_yesterday)
        else -> date.format(
            DateTimeFormatter.ofPattern("d MMMM yyyy", res.configuration.locales.get(0) ?: Locale.getDefault())
        )
    }
}

/** Convertit des millis en ISO 8601 a midi UTC (evite les surprises TZ). */
/** Integer division rounded up, as the server splits points (`math.ceil`, completions.py). */
private fun ceilDiv(a: Int, b: Int): Int = (a + b.coerceAtLeast(1) - 1) / b.coerceAtLeast(1)

private fun millisToIsoNoonUtc(millis: Long): String =
    com.torchons.app.util.CompletionDateLabels.millisToIsoNoonUtc(millis)

/**
 * Ligne "Tu gagneras X pts" + raison (bonus/malus/neutre/1ere/perso).
 * Affichee au bas du dialog de completion, dynamique selon date et N
 * participants. Backend authoritative via /completion-preview.
 */
@Composable
private fun PreviewPointsLine(
    preview: CompletionPreview?,
    failed: Boolean,
    /** How many people the points are split between (the preview was asked for that many). */
    participants: Int = 1,
    earnText: @Composable (points: Int) -> String = { pts ->
        pluralStringResource(R.plurals.completion_preview_earn, pts, pts)
    },
) {
    if (preview == null) {
        // Loader / fallback.
        Text(
            text = if (failed) stringResource(R.string.completion_preview_unavailable)
            else stringResource(R.string.completion_preview_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // finalPoints is ONE person's share (the server splits rounding up), basePoints the whole
    // task's: the bonus/malus is read against the share of the base, else two people saw
    // « -4 pts » on a plain first completion (2026-11-18). Only a real bonus or malus is
    // explained; « first time, full points » or a neutral case says nothing.
    val share = if (preview.basePoints > 0) ceilDiv(preview.basePoints, participants) else 0
    val delta = preview.finalPoints - share
    val (emoji, reasonLabel, reasonColor) = when {
        preview.reason == "BONUS_RETARD" && delta > 0 ->
            Triple("🔥", stringResource(R.string.completion_preview_reason_bonus_late), Color(0xFF2E7D32))
        preview.reason == "MALUS_AVANCE" && delta < 0 ->
            Triple("⏱", stringResource(R.string.completion_preview_reason_malus_early), Color(0xFFEF6C00))
        else -> Triple(null, null, MaterialTheme.colorScheme.onSurface)
    }

    var showSoloInfo by remember { mutableStateOf(false) }
    val isSolo = preview.reason == "PERSO" || preview.finalPoints == 0

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = earnText(preview.finalPoints),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSolo) MaterialTheme.colorScheme.onSurfaceVariant else reasonColor,
            )
            // Icone (i) cliquable pour expliquer le 0 pt des taches solo.
            if (isSolo) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.completion_preview_solo_info_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { showSoloInfo = true },
                )
            }
        }
        if (reasonLabel != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (emoji != null) {
                    Text(text = emoji, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                val deltaLabel = when {
                    delta > 0 -> pluralStringResource(
                        R.plurals.completion_preview_delta_positive,
                        delta,
                        delta,
                        reasonLabel,
                    )
                    delta < 0 -> stringResource(
                        R.string.completion_preview_delta_negative_format,
                        delta,
                        reasonLabel,
                    )
                    else -> reasonLabel
                }
                Text(
                    text = deltaLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = reasonColor,
                )
            }
        }
    }

    if (showSoloInfo) {
        SoloPointsInfoDialog(onDismiss = { showSoloInfo = false })
    }
}

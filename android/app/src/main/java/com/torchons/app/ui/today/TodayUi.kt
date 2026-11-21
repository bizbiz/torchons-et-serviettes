package com.torchons.app.ui.today

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.core.today.TodayTap
import com.torchons.app.core.today.handOffRecipients
import com.torchons.app.core.today.joinNames
import com.torchons.app.core.today.todayTap
import com.torchons.app.data.model.Delegation
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The « ! » of a task (docs/plans/a-faire-aujourdhui.md): grey when the task is not handed,
 * red when it is. What a tap does is decided by core/today/TodayRules.todayTap.
 */
@Composable
fun TodayButton(lit: Boolean, mine: Boolean, onClick: () -> Unit, size: Dp = 40.dp) {
    val cd = stringResource(todayButtonDescription(lit, mine))
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size).semantics { contentDescription = cd },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (lit) TaskUrgencyCalculator.ROUGE else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (lit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text = "!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun nameOf(res: Resources, id: String, names: Map<String, String>, me: String): String =
    if (id == me) res.getString(R.string.today_you) else names[id] ?: "?"

/** « hier », « mardi dernier »…, or null when the delegation is from today. */
private fun sinceLabel(res: Resources, createdAt: String, today: LocalDate): String? {
    val day = runCatching { Instant.parse(createdAt).atZone(ZoneId.systemDefault()).toLocalDate() }
        .getOrNull() ?: return null
    return if (!day.isBefore(today)) null else CompletionDateLabels.relativeDayLabel(res, day, today)
}

/** « Confiée à Bob », « Confiée à Bob & toi depuis hier »: the line under a handed task's title. */
fun handedToLine(
    res: Resources, d: Delegation, names: Map<String, String>, me: String, today: LocalDate = LocalDate.now(),
): String {
    val who = joinNames(d.recipientIds.map { nameOf(res, it, names, me) })
    return sinceLabel(res, d.createdAt, today)
        ?.let { res.getString(R.string.today_handed_to_since_format, who, it) }
        ?: res.getString(R.string.today_handed_to_format, who)
}

/** « Confiée par Alice (depuis hier) »: the recipient's view, on the home stack. */
fun handedByLine(
    res: Resources, d: Delegation, names: Map<String, String>, today: LocalDate = LocalDate.now(),
): String {
    val who = names[d.authorId] ?: "?"
    return sinceLabel(res, d.createdAt, today)
        ?.let { res.getString(R.string.today_handed_by_since_format, who, it) }
        ?: res.getString(R.string.today_handed_by_format, who)
}

/** The « ! » of a task card: lit when the task is handed, with the line under its title. */
/** [mine]: I handed it, so a tap takes it back (todayTap); otherwise a red « ! » only informs. */
data class TodayCardState(val lit: Boolean, val line: String?, val mine: Boolean = false)

/**
 * What a « ! » without visible text says to TalkBack. A red one offers « Annuler » to its author
 * only: anyone else's tap shows who handed it (core/today/TodayRules.todayTap).
 */
fun todayButtonDescription(lit: Boolean, mine: Boolean): Int = when {
    !lit -> R.string.today_button_cd
    mine -> R.string.today_button_cancel_cd
    else -> R.string.today_button_info_cd
}

/**
 * The card's « ! », or null to hide it: nothing handed and nobody else to hand it to (a solo
 * task of mine). A task someone else handed stays lit even when I could not hand it myself.
 */
fun todayCardState(
    res: Resources,
    assignedToIds: List<String>,
    delegation: Delegation?,
    names: Map<String, String>,
    me: String,
): TodayCardState? = when {
    delegation != null -> TodayCardState(
        lit = true, line = handedToLine(res, delegation, names, me), mine = delegation.authorId == me,
    )
    handOffRecipients(assignedToIds, names.keys.toList(), me).isEmpty() -> null
    else -> TodayCardState(lit = false, line = null)
}

/** Dialogs a « ! » opens: whom to hand the task to, or who handed it to whom. */
@Stable
class TodayActionState {
    /** (task id, candidate ids) while the « À qui ? » dialog is open. */
    var picking by mutableStateOf<Pair<String, List<String>>?>(null)
    var info by mutableStateOf<Delegation?>(null)

    fun onTap(
        taskId: String,
        tap: TodayTap?,
        onHand: (taskId: String, recipientIds: List<String>) -> Unit,
        onCancel: (Delegation) -> Unit,
    ) {
        when (tap) {
            is TodayTap.HandTo -> onHand(taskId, tap.recipientIds)
            is TodayTap.Pick -> picking = taskId to tap.candidateIds
            is TodayTap.Cancel -> onCancel(tap.delegation)
            is TodayTap.Info -> info = tap.delegation
            null -> Unit
        }
    }

    /** A tap on the « ! » of [taskId], seen by [me] among [memberIds]. */
    fun onTaskTap(
        taskId: String,
        assignedToIds: List<String>,
        delegation: Delegation?,
        memberIds: List<String>,
        me: String,
        today: TodayDelegations,
    ) {
        val tap = todayTap(delegation, handOffRecipients(assignedToIds, memberIds, me), me)
        onTap(taskId, tap, today::hand, today::cancel)
    }
}

@Composable
fun rememberTodayActionState(): TodayActionState = remember { TodayActionState() }

@Composable
fun TodayActionDialogs(
    state: TodayActionState,
    titleOf: (taskId: String) -> String,
    names: Map<String, String>,
    me: String,
    onHand: (taskId: String, recipientIds: List<String>) -> Unit,
) {
    val res = LocalContext.current.resources
    state.picking?.let { (taskId, candidates) ->
        HandOffDialog(
            taskTitle = titleOf(taskId),
            candidates = candidates.map { it to (names[it] ?: "?") },
            onDismiss = { state.picking = null },
            onConfirm = { ids -> onHand(taskId, ids); state.picking = null },
        )
    }
    state.info?.let { d ->
        val to = joinNames(d.recipientIds.map { nameOf(res, it, names, me) })
        AlertDialog(
            onDismissRequest = { state.info = null },
            title = { Text(stringResource(R.string.today_title)) },
            text = { Text(stringResource(R.string.today_info_format, names[d.authorId] ?: "?", to)) },
            confirmButton = {
                TextButton(onClick = { state.info = null }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/** « À qui ? »: every possible recipient, all checked; at least one to confirm. */
@Composable
private fun HandOffDialog(
    taskTitle: String,
    candidates: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var checked by remember(candidates) { mutableStateOf(candidates.map { it.first }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.today_pick_title), fontWeight = FontWeight.Bold)
                Text(
                    taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Text(stringResource(R.string.today_pick_who), style = MaterialTheme.typography.labelLarge)
                candidates.forEach { (id, name) ->
                    val toggle = { checked = if (id in checked) checked - id else checked + id }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = id in checked, onCheckedChange = { toggle() })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            val chosen = candidates.map { it.first }.filter { it in checked }
            Button(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen) }) {
                Text(stringResource(R.string.today_pick_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

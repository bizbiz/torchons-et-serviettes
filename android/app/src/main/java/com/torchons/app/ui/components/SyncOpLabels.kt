package com.torchons.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.core.sync.PendingOpKind
import com.torchons.app.core.sync.RejectedSyncOp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Fait : Changer les serviettes (Bob)" — what an op is about. A setting op is named by the setting
 * ("Réglage : heure du résumé quotidien"), an op with no task by its kind alone: neither has a task
 * to look up, and "tâche inconnue" was the wrong thing to say about them.
 */
@Composable
internal fun syncOpLabel(
    kind: PendingOpKind,
    taskTitle: String?,
    memberName: String?,
    settingKey: String? = null,
): String {
    val kindText = stringResource(
        when (kind) {
            PendingOpKind.TASK_CREATE -> R.string.sync_op_task_create
            PendingOpKind.TASK_EDIT -> R.string.sync_op_task_edit
            PendingOpKind.TASK_DELETE -> R.string.sync_op_task_delete
            PendingOpKind.COMPLETION_ADD -> R.string.sync_op_completion_add
            PendingOpKind.COMPLETION_EDIT -> R.string.sync_op_completion_edit
            PendingOpKind.COMPLETION_DELETE -> R.string.sync_op_completion_delete
            PendingOpKind.FREQUENCY_VOTE -> R.string.sync_op_frequency_vote
            PendingOpKind.EFFORT_VOTE -> R.string.sync_op_effort_vote
            PendingOpKind.AFFINITY_VOTE -> R.string.sync_op_affinity_vote
            PendingOpKind.PROMPT_ANSWER -> R.string.sync_op_prompt_answer
            PendingOpKind.SETTING_CHANGE -> R.string.sync_op_setting_change
            PendingOpKind.DELEGATION_ADD -> R.string.sync_op_delegation_add
            PendingOpKind.DELEGATION_CANCEL -> R.string.sync_op_delegation_cancel
            PendingOpKind.OTHER -> R.string.sync_op_other
        }
    )
    if (kind == PendingOpKind.SETTING_CHANGE) {
        val settingRes = settingLabelRes(settingKey) ?: return stringResource(R.string.sync_op_setting_generic)
        return stringResource(R.string.sync_op_label_format, kindText, stringResource(settingRes))
    }
    if (taskTitle == null && kind in KINDS_WITHOUT_TASK) return kindText
    val title = taskTitle ?: stringResource(R.string.sync_op_unknown_task)
    return if (memberName != null) {
        stringResource(R.string.sync_op_label_member_format, kindText, title, memberName)
    } else {
        stringResource(R.string.sync_op_label_format, kindText, title)
    }
}

/**
 * Ops the server refused for good: already out of the queue and undone on this
 * phone. Shown until the dialog is closed, so a refusal is never silent.
 */
@Composable
internal fun RejectedOpsSection(rejected: List<RejectedSyncOp>) {
    if (rejected.isEmpty()) return
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.sync_rejected_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        text = stringResource(R.string.sync_rejected_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rejected.forEach { op ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                text = syncOpLabel(op.kind, op.taskTitle, memberName = null, settingKey = op.settingKey),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.sync_rejected_meta, fmt.format(Date(op.rejectedAtMs))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            op.reason?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

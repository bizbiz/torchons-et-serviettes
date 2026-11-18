package com.torchons.app.ui.taskdetail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.CompletionDialog
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.moduleEnabled
import com.torchons.app.ui.today.TodayActionDialogs
import com.torchons.app.ui.today.TodayActionState
import com.torchons.app.ui.today.todayCardState
import com.torchons.app.ui.tasklist.TwoPersonTogetherDatePicker
import com.torchons.app.util.TaskUrgencyCalculator

/*
 * The task page's « Date + Qui » dialog and « ! » button (docs/plans/a-faire-aujourdhui.md).
 * They replaced « Déjà fait » / « Fait ensemble » and the two-member date picker on 2026-11-18;
 * kept out of TaskDetailScreen, which is at its size cap.
 */

/**
 * « Date + Qui »: one completion for one member checked, a collective one for several.
 * [together] (the page's « Fait ensemble »): every assignee checked at opening, and in a
 * two-member household straight to the date for both, as before 2026-11-18.
 */
@Composable
internal fun TaskDetailCompletionDialog(
    viewModel: TaskDetailViewModel,
    currentUserId: String,
    gamificationVisible: Boolean,
    together: Boolean,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val task = uiState.task ?: return
    val memberIds = uiState.userNames.keys.toList()
    if (together && memberIds.size == 2) {
        TwoPersonTogetherDatePicker(
            onConfirm = { isoDate -> viewModel.completeTaskCollective(memberIds, isoDate) },
            onDismiss = onDismiss,
        )
        return
    }
    CompletionDialog(
        initialParticipantIds = task.assignedToIds.ifEmpty { memberIds }.toSet().takeIf { together },
        taskTitle = task.title,
        allMembers = uiState.userNames.entries.map { it.key to it.value },
        assignedMemberIds = task.assignedToIds.toSet(),
        currentUserId = currentUserId,
        onDismiss = onDismiss,
        onConfirm = { participantIds, isoDate, preview ->
            if (participantIds.size == 1) {
                viewModel.completeTaskAtDate(
                    isoDate = isoDate,
                    completedBy = participantIds.first(),
                    previewPoints = preview?.finalPoints,
                    previewReason = preview?.reason,
                )
            } else {
                viewModel.completeTaskCollective(
                    participantUserIds = participantIds,
                    isoDate = isoDate,
                    previewPoints = preview?.finalPoints,
                    previewReason = preview?.reason,
                )
            }
            onDismiss()
        },
        previewProvider = { isoDate, numParticipants ->
            viewModel.fetchCompletionPreview(task.id, isoDate, numParticipants)
        },
        gamificationVisible = gamificationVisible,
    )
}

/**
 * The page's « ! »: grey « À faire aujourd'hui », or red with « Confiée à X » when handed.
 * Nothing when the household switched the feature off, or nobody else sees the task.
 */
@Composable
internal fun TaskDetailTodayButton(
    viewModel: TaskDetailViewModel,
    currentUserId: String,
    actions: TodayActionState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val delegations by viewModel.today.byTask.collectAsState()
    val task = uiState.task ?: return
    if (!moduleEnabled(Modules.TODAY)) return
    val res = LocalContext.current.resources
    val delegation = delegations[task.id]
    val state = todayCardState(res, task.assignedToIds, delegation, uiState.userNames, currentUserId) ?: return
    TodayActionDialogs(
        state = actions,
        titleOf = { task.title },
        names = uiState.userNames,
        me = currentUserId,
        onHand = viewModel.today::hand,
    )
    OutlinedButton(
        onClick = {
            actions.onTaskTap(
                task.id, task.assignedToIds, delegation, uiState.userNames.keys.toList(), currentUserId,
                viewModel.today,
            )
        },
        modifier = modifier,
        colors = if (state.lit) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = TaskUrgencyCalculator.ROUGE,
                contentColor = Color.White,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text("!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(6.dp))
        Text(state.line ?: stringResource(R.string.today_title), maxLines = 2)
    }
}

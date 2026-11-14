package com.torchons.app.ui.taskdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.ui.common.EffortInfoDialog
import com.torchons.app.ui.common.FrequencyInfoDialog
import com.torchons.app.ui.common.RevoteNotice

/**
 * The "+ d'info" dialogs of the frequency and effort votes, with the feedback of a re-vote
 * request: the status line, and the answer when the push carrying it reaches the open dialog.
 */
@Composable
fun VoteInfoDialogs(
    uiState: TaskDetailUiState,
    currentUserId: String?,
    gamificationVisible: Boolean,
    viewModel: TaskDetailViewModel,
    revoteViewModel: RevoteFeedbackViewModel = hiltViewModel(),
) {
    val notice by revoteViewModel.notice.collectAsState()
    val task = uiState.task ?: return
    val members = task.assignedToIds.map { uid -> uid to (uiState.userNames[uid] ?: "?") }
    val requestSent = stringResource(R.string.revote_request_sent)

    // A new status (request sent, answer received) changes who is pending: fetch the votes again.
    LaunchedEffect(notice) { if (notice != null) viewModel.refreshVotes() }

    // The dialog opens on a request made earlier, still pending: wait for its answer here too.
    val freq = uiState.frequencyVotes?.takeIf { uiState.showFrequencyInfo }
    val effort = uiState.effortVotes?.takeIf { uiState.showEffortInfo }
    val pendingId = (freq?.pendingRevoteUserIds ?: effort?.pendingRevoteUserIds)
        ?.firstOrNull { it != currentUserId }
    val kind = if (freq != null) "frequency" else "effort"
    LaunchedEffect(pendingId, kind) {
        if (pendingId != null) revoteViewModel.onPendingSeen(pendingId, uiState.userNames[pendingId] ?: "?", kind)
    }

    if (freq != null) {
        FrequencyInfoDialog(
            state = freq,
            foyerMembers = members,
            currentUserId = currentUserId,
            onAskRevote = { uid -> ask(revoteViewModel, viewModel, uiState, uid, "frequency", requestSent) },
            onEditMyVote = {
                revoteViewModel.onDialogClosed()
                viewModel.dismissFrequencyInfo()
                viewModel.openFrequencyVoteDialog()
            },
            onDismiss = {
                revoteViewModel.onDialogClosed()
                viewModel.dismissFrequencyInfo()
            },
            revoteNotice = notice,
        )
    }
    if (effort != null) {
        EffortInfoDialog(
            state = effort,
            foyerMembers = members,
            currentUserId = currentUserId,
            onAskRevote = { uid -> ask(revoteViewModel, viewModel, uiState, uid, "effort", requestSent) },
            onEditMyVote = {
                revoteViewModel.onDialogClosed()
                viewModel.dismissEffortInfo()
                viewModel.openEffortVoteDialog()
            },
            onDismiss = {
                revoteViewModel.onDialogClosed()
                viewModel.dismissEffortInfo()
            },
            gamificationVisible = gamificationVisible,
            revoteNotice = notice,
        )
    }
}

private fun ask(
    revote: RevoteFeedbackViewModel,
    detail: TaskDetailViewModel,
    uiState: TaskDetailUiState,
    userId: String,
    kind: String,
    sentMessage: String,
) {
    revote.request(userId, uiState.userNames[userId] ?: "?", kind) { detail.showMessage(sentMessage) }
}

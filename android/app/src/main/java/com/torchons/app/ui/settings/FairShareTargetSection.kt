package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.torchons.app.R
import com.torchons.app.ui.common.dismissKeyboardThen
import com.torchons.app.ui.common.submitKeyboardActions
import androidx.compose.ui.text.input.ImeAction
import com.torchons.app.data.model.FairShareHistoryEntry
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.ReloadOnReconnect

/**
 * Target share of each member of the household (percent per member, total 100), with the
 * (i) that explains fair versus equal and a button for the history of changes.
 *
 * Shown by the household screen only when the "repartition" module is on. Hidden here for a
 * household of one: there is nobody to share with. Offline it says so and offers a retry.
 */
@Composable
fun FairShareTargetSection(viewModel: FairShareTargetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()

    ReloadOnReconnect(hasError = state.load == FairShareLoad.UNAVAILABLE) { viewModel.retryIfUnavailable() }

    // The bottom tabs keep this ViewModel alive: a block opened offline retries when the
    // screen comes back instead of staying unavailable until the app is restarted.
    LifecycleResumeEffect(Unit) {
        viewModel.retryIfUnavailable()
        onPauseOrDispose {}
    }

    if (state.load == FairShareLoad.READY && !isFairShareBlockVisible(state.members.size)) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.fair_share_target_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                InfoTooltip(
                    title = stringResource(R.string.fair_share_target_info_title),
                    description = stringResource(R.string.fair_share_target_info_text),
                )
            }
            Spacer(Modifier.height(8.dp))
            when (state.load) {
                FairShareLoad.LOADING -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                FairShareLoad.UNAVAILABLE -> UnavailableBlock(
                    messageRes = R.string.fair_share_target_unavailable,
                    onRetry = viewModel::load,
                )
                FairShareLoad.READY -> SharesEditor(state, viewModel)
            }
        }
    }

    if (history.open) {
        HistoryDialog(history, onRetry = viewModel::openHistory, onClose = viewModel::closeHistory)
    }
}

@Composable
private fun UnavailableBlock(messageRes: Int, onRetry: () -> Unit) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onRetry) {
        Text(stringResource(R.string.fair_share_target_retry))
    }
}

@Composable
private fun MemberRow(member: FairShareMember, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = member.input,
            onValueChange = onChange,
            singleLine = true,
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = submitKeyboardActions(),
            modifier = Modifier.width(112.dp),
        )
    }
}

@Composable
private fun SharesEditor(state: FairShareTargetUiState, viewModel: FairShareTargetViewModel) {
    state.members.forEach { member ->
        MemberRow(member) { viewModel.setInput(member.userId, it) }
    }
    val total = totalPercent(state.members.map { it.input })
    val canSave = canSaveShares(total, state.saving)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.fair_share_target_total_format, total),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = if (total == FAIR_SHARE_TOTAL_PERCENT) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    state.messageRes?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.messageIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = dismissKeyboardThen(viewModel::save), enabled = canSave, modifier = Modifier.weight(1f)) {
            Text(
                if (canSave || state.saving) {
                    stringResource(R.string.common_save)
                } else {
                    stringResource(R.string.fair_share_target_save_total_format, total)
                },
            )
        }
        OutlinedButton(onClick = viewModel::openHistory) {
            Text(stringResource(R.string.fair_share_target_history_button))
        }
    }
}

@Composable
private fun HistoryDialog(history: FairShareHistoryUiState, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.fair_share_target_history_title)) },
        text = {
            when {
                history.load == FairShareLoad.LOADING -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                history.load == FairShareLoad.UNAVAILABLE -> Column {
                    UnavailableBlock(R.string.fair_share_target_history_unavailable, onRetry)
                }
                history.entries.isEmpty() -> Text(stringResource(R.string.fair_share_target_history_empty))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(history.entries, key = { it.id }) { HistoryLine(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun HistoryLine(entry: FairShareHistoryEntry) {
    Column {
        Text(
            text = stringResource(
                R.string.fair_share_target_history_line_format,
                historyDateText(entry.changedAt),
                entry.changedByName,
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        entry.shares.forEach { share ->
            Text(
                text = stringResource(
                    R.string.fair_share_target_history_share_format,
                    share.displayName,
                    shareText(share.before),
                    shareText(share.after),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

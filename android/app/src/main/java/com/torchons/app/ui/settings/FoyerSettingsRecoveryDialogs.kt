package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.UserErrorDialogText

/**
 * Dialogs des flows "Regenerer code de recuperation" et "Quitter le foyer".
 * Reprend exactement la sequence d'AlertDialog de SecuritySection (confirm
 * -> loading -> success/switched -> error), pilotee par regenerateState /
 * leaveState du SettingsViewModel. La card declenchant ces flows vit
 * directement dans FoyerSettingsScreen (groupes 12 et 10).
 */
@Composable
internal fun RecoveryAndLeaveDialogs(
    viewModel: SettingsViewModel,
    onLeftFoyer: () -> Unit,
    showLeaveConfirm: Boolean,
    onDismissLeaveConfirm: () -> Unit,
    showRegenerateConfirm: Boolean,
    onDismissRegenerateConfirm: () -> Unit,
) {
    RegenerateCodeDialogs(viewModel, showRegenerateConfirm, onDismissRegenerateConfirm)
    LeaveFoyerDialogs(viewModel, onLeftFoyer, showLeaveConfirm, onDismissLeaveConfirm)
}

@Composable
private fun RegenerateCodeDialogs(
    viewModel: SettingsViewModel,
    showRegenerateConfirm: Boolean,
    onDismissRegenerateConfirm: () -> Unit,
) {
    val regenerateState by viewModel.regenerateState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // --- Regenerer : confirmation ---
    if (showRegenerateConfirm &&
        regenerateState is SettingsViewModel.RegenerateState.Idle
    ) {
        AlertDialog(
            onDismissRequest = onDismissRegenerateConfirm,
            title = { Text(stringResource(R.string.recovery_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.recovery_dialog_confirm_message))
            },
            confirmButton = {
                Button(onClick = {
                    onDismissRegenerateConfirm()
                    viewModel.regenerateRecoveryCode()
                }) { Text(stringResource(R.string.foyer_recovery_button)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRegenerateConfirm) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (regenerateState is SettingsViewModel.RegenerateState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recovery_dialog_loading_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    val regenSuccess = regenerateState as? SettingsViewModel.RegenerateState.Success
    if (regenSuccess != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text(stringResource(R.string.recovery_dialog_success_title)) },
            text = {
                Column {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = regenSuccess.recoveryCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.recovery_dialog_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.recovery_dialog_copy))
                        }
                        OutlinedButton(
                            onClick = { shareCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.recovery_dialog_share))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) {
                    Text(stringResource(R.string.recovery_dialog_noted))
                }
            },
        )
    }
    val regenError = regenerateState as? SettingsViewModel.RegenerateState.Error
    if (regenError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text(stringResource(R.string.recovery_dialog_error_title)) },
            text = { UserErrorDialogText(regenError.error, "SettingsRecovery") },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun LeaveFoyerDialogs(
    viewModel: SettingsViewModel,
    onLeftFoyer: () -> Unit,
    showLeaveConfirm: Boolean,
    onDismissLeaveConfirm: () -> Unit,
) {
    val leaveState by viewModel.leaveState.collectAsState()

    LaunchedEffect(leaveState) {
        if (leaveState is SettingsViewModel.LeaveState.LoggedOut) {
            onLeftFoyer()
        }
    }

    // --- Quitter le foyer ---
    if (showLeaveConfirm && leaveState is SettingsViewModel.LeaveState.Idle) {
        AlertDialog(
            onDismissRequest = onDismissLeaveConfirm,
            title = { Text(stringResource(R.string.leave_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.leave_dialog_confirm_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissLeaveConfirm()
                        viewModel.leaveFoyer()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.leave_dialog_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissLeaveConfirm) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.leave_dialog_loading_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.SwitchedFoyer) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text(stringResource(R.string.leave_dialog_switched_title)) },
            text = { Text(stringResource(R.string.leave_dialog_switched_message)) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    val leaveError = leaveState as? SettingsViewModel.LeaveState.Error
    if (leaveError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text(stringResource(R.string.recovery_dialog_error_title)) },
            text = { UserErrorDialogText(leaveError.error, "SettingsLeaveFoyer") },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

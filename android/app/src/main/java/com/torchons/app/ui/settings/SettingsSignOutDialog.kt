package com.torchons.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torchons.app.ui.devices.SettingsSignOutTexts
import com.torchons.app.ui.devices.SignOutDialog
import com.torchons.app.ui.devices.shownSignOutSyncState

/**
 * The confirmation of Settings "Sign out": the same dialog and logic as "My devices" (SignOutFlow),
 * so a sign-out never drops unsent changes without saying so (emulator re-test, 2026-11-20).
 */
@Composable
internal fun SettingsSignOutDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val pending by viewModel.pendingOps.collectAsStateWithLifecycle()
    val syncState by viewModel.signOutSync.collectAsStateWithLifecycle()
    SignOutDialog(
        texts = SettingsSignOutTexts,
        pending = pending,
        syncState = shownSignOutSyncState(syncState, pending),
        onSyncFirst = viewModel::syncBeforeSignOut,
        onConfirm = {
            onDismiss()
            viewModel.confirmSignOut(onSignedOut)
        },
        onDismiss = {
            onDismiss()
            viewModel.resetSignOut()
        },
    )
}

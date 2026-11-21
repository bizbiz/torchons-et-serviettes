package com.torchons.app.ui.devices

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/** What a sign-out dialog says about ITS sign-out (wipe of this device, or only a sign-out). */
internal data class SignOutTexts(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @PluralsRes val pending: Int,
    @StringRes val syncFailed: Int,
)

/** "Sign out and cut this device" (My devices): the local data is erased. */
internal val CutDeviceSignOutTexts = SignOutTexts(
    title = R.string.mydevices_signout_confirm_title,
    body = R.string.mydevices_signout_confirm_body,
    pending = R.plurals.mydevices_signout_pending,
    syncFailed = R.string.mydevices_signout_sync_failed,
)

/** Settings "Sign out": the queue stays on the phone and leaves at the next sign-in. */
internal val SettingsSignOutTexts = SignOutTexts(
    title = R.string.settings_logout_confirm_title,
    body = R.string.settings_logout_confirm_body,
    pending = R.plurals.settings_logout_pending,
    syncFailed = R.string.settings_logout_sync_failed,
)

/**
 * The confirmation every sign-out goes through (see [SignOutFlow]): with changes waiting it says
 * how many, offers to sync first, and signs out anyway only on request.
 */
@Composable
internal fun SignOutDialog(
    texts: SignOutTexts,
    pending: Int,
    syncState: SignOutSyncState,
    onSyncFirst: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(texts.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(texts.body))
                if (pending > 0) {
                    Text(
                        text = pluralStringResource(texts.pending, pending, pending),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when (syncState) {
                    SignOutSyncState.SYNCING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.mydevices_signout_syncing))
                    }
                    SignOutSyncState.GAVE_UP -> Text(
                        text = stringResource(texts.syncFailed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    SignOutSyncState.IDLE -> Unit
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (pending > 0) {
                    TextButton(onClick = onSyncFirst, enabled = syncState != SignOutSyncState.SYNCING) {
                        Text(stringResource(R.string.mydevices_signout_sync_first))
                    }
                }
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(
                            if (pending > 0) R.string.mydevices_signout_anyway else R.string.mydevices_confirm,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mydevices_cancel)) }
        },
    )
}

package com.torchons.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torchons.app.R
import com.torchons.app.data.model.DeviceDto
import com.torchons.app.ui.common.UserErrorBlock
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen

/**
 * « Mes appareils » : la liste des sessions de ce compte, et de quoi les couper.
 *
 * Le nom seul ne distingue rien — deux telephones du foyer portent souvent le
 * meme modele, et `Build.MODEL`, `device_name` et `bluetooth_name` rendaient
 * tous les trois « Pixel 7 » a la mesure du 2026-09-02. Ce qui discrimine ici :
 * le marqueur « cet appareil », puis la date d'AJOUT — au moment precis ou l'on
 * revoque un ancien telephone, les deux viennent d'etre vus, mais un seul vient
 * d'etre ajoute.
 *
 * Detail : docs/appareils.fr.md
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingRevoke by remember { mutableStateOf<DeviceDto?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mydevices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = { HelpButton(HelpScreen.DEVICES) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> Column {
                    state.error?.let { UserErrorBlock(it, "Devices", color = MaterialTheme.colorScheme.onSurface) }
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.mydevices_retry))
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.devices, key = { it.deviceId }) { device ->
                        DeviceRow(device = device, onRevoke = { pendingRevoke = device })
                    }
                }
            }

            OutlinedButton(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mydevices_add))
            }
            TextButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.mydevices_signout_this),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    pendingRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(stringResource(R.string.mydevices_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.mydevices_revoke_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(device.deviceId)
                    pendingRevoke = null
                }) { Text(stringResource(R.string.mydevices_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.mydevices_cancel))
                }
            },
        )
    }

    if (confirmSignOut) {
        val pending by viewModel.pendingOps.collectAsStateWithLifecycle()
        val syncState by viewModel.signOutSync.collectAsStateWithLifecycle()
        SignOutDialog(
            pending = pending,
            syncState = syncState,
            onSyncFirst = viewModel::syncFirst,
            onConfirm = {
                confirmSignOut = false
                viewModel.resetSignOutSync()
                viewModel.signOutThisDevice()
            },
            onDismiss = {
                confirmSignOut = false
                viewModel.resetSignOutSync()
            },
        )
    }
}

/**
 * The sign-out wipes the phone, unsent changes included (D9, 2026-11-20 audit): with changes
 * waiting, the dialog says how many, offers to sync first, and signs out anyway only on request.
 */
@Composable
private fun SignOutDialog(
    pending: Int,
    syncState: SignOutSyncState,
    onSyncFirst: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mydevices_signout_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mydevices_signout_confirm_body))
                if (pending > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.mydevices_signout_pending, pending, pending),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when (syncState) {
                    SignOutSyncState.SYNCING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.mydevices_signout_syncing))
                    }
                    SignOutSyncState.GAVE_UP -> Text(
                        text = stringResource(R.string.mydevices_signout_sync_failed),
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

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Smartphone, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName ?: device.platform,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (device.isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mydevices_this_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.mydevices_added_on, shortDate(device.firstSeen)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.revokedAt != null) {
                    Text(
                        text = stringResource(
                            R.string.mydevices_revoked_on, shortDate(device.revokedAt)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.mydevices_last_seen, shortDate(device.lastSeen)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!device.isCurrent && device.revokedAt == null) {
                TextButton(onClick = onRevoke) {
                    Text(
                        stringResource(R.string.mydevices_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** `2026-09-02T14:41:02+00:00` -> `2026-09-02`. Sans dependance de formatage. */
private fun shortDate(iso: String): String = iso.take(10)

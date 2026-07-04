package com.torchons.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.ui.theme.FreshnessOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Header sync-status icon replacing the old OfflineBanner (which lived in
 * the layout flow and pushed the whole UI down when appearing). Three
 * states:
 *  - up to date  : CloudDone, discreet
 *  - syncing     : rotating Sync icon (pending offline ops being pushed)
 *  - failure     : CloudOff / SyncProblem, orange (server unreachable, or
 *                  ops stuck in retry)
 *
 * Tapping the icon opens [SyncStatusDialog] with details and, when
 * offline, a two-probe diagnosis (internet down vs server down).
 */
@Composable
fun SyncStatusIcon(
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsState()
    val pendingOps by viewModel.pendingOps.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    val isOffline = state is ConnectionState.Offline
    val pendingCount = pendingOps.size
    val stuck = pendingOps.any { it.attemptCount >= 2 }
    val syncing = !isOffline && pendingCount > 0 && !stuck

    val icon = when {
        isOffline -> Icons.Default.CloudOff
        stuck -> Icons.Default.SyncProblem
        syncing -> Icons.Default.Sync
        else -> Icons.Default.CloudDone
    }
    val tint = when {
        isOffline || stuck -> FreshnessOrange
        syncing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    // Continuous rotation while pushing ops, so the sync is visible without
    // stealing any layout space.
    val rotation = if (syncing) {
        val transition = rememberInfiniteTransition(label = "syncRotation")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "syncAngle",
        )
        angle
    } else 0f

    IconButton(onClick = { showDialog = true }) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.sync_status_cd),
            tint = tint,
            modifier = Modifier.rotate(rotation),
        )
    }

    if (showDialog) {
        SyncStatusDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.clearDiagnosis()
                showDialog = false
            },
        )
    }
}

@Composable
private fun SyncStatusDialog(
    viewModel: ConnectionViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.connectionState.collectAsState()
    val pendingOps by viewModel.pendingOps.collectAsState()
    val cacheTs by viewModel.cacheTimestamp.collectAsState()
    val diagnosis by viewModel.diagnosis.collectAsState()

    val offline = state as? ConnectionState.Offline
    val pendingCount = pendingOps.size
    val lastError = pendingOps.firstOrNull { it.lastError != null }?.lastError

    // Run the connectivity diagnosis as soon as the dialog opens while the
    // backend is unreachable (and again after a retry flips us back).
    LaunchedEffect(offline != null) {
        if (offline != null) viewModel.runDiagnosis()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Headline state
                Text(
                    text = when {
                        offline != null -> stringResource(R.string.sync_state_offline)
                        pendingCount > 0 -> stringResource(R.string.sync_state_syncing)
                        else -> stringResource(R.string.sync_state_up_to_date)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (offline != null) {
                    Spacer(Modifier.height(4.dp))
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    Text(
                        text = stringResource(
                            R.string.sync_offline_since_format,
                            fmt.format(Date(offline.sinceEpochMs)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    cacheTs?.let {
                        val fullFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                        Text(
                            text = stringResource(R.string.sync_data_from_format, fullFmt.format(Date(it))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pendingCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(R.plurals.sync_pending_ops, pendingCount, pendingCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.sync_auto_resync_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (offline != null) {
                    Spacer(Modifier.height(8.dp))
                    when (val d = diagnosis) {
                        null -> Text(
                            text = stringResource(R.string.sync_diag_running),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            text = when {
                                !d.internetOk -> stringResource(R.string.sync_diag_no_internet)
                                !d.serverOk -> stringResource(R.string.sync_diag_server_down)
                                else -> stringResource(R.string.sync_diag_all_ok)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (lastError != null && (offline != null || pendingOps.any { it.attemptCount >= 2 })) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_last_error_format, lastError),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_understood))
            }
        },
        dismissButton = {
            if (offline != null || pendingCount > 0) {
                TextButton(onClick = {
                    viewModel.retrySync()
                    viewModel.runDiagnosis()
                }) {
                    Text(stringResource(R.string.sync_retry))
                }
            }
        },
    )
}

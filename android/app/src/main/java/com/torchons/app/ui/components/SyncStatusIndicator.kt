package com.torchons.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.BuildConfig
import com.torchons.app.R
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.core.sync.PendingOpKind
import com.torchons.app.core.sync.PendingOpRow
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
    val pendingRows by viewModel.pendingRows.collectAsState()
    val lastContactMs by viewModel.lastServerContactMs.collectAsState()
    val lastPushMs by viewModel.lastSyncPushMs.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    var discardCandidate by remember { mutableStateOf<PendingOpRow?>(null) }

    val offline = state as? ConnectionState.Offline
    val pendingCount = pendingOps.size

    // Run the connectivity diagnosis as soon as the dialog opens while the
    // backend is unreachable (and again after a retry flips us back).
    LaunchedEffect(offline != null) {
        if (offline != null) viewModel.runDiagnosis()
        viewModel.loadServerInfo()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Headline state
                Text(
                    text = when {
                        offline != null -> stringResource(R.string.sync_state_offline)
                        pendingCount > 0 -> stringResource(R.string.sync_state_syncing)
                        else -> stringResource(R.string.sync_state_up_to_date)
                    },
                    style = MaterialTheme.typography.bodyLarge,
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
                // The queue itself: what waits, since when, and why it failed.
                if (pendingRows.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.sync_queue_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    pendingRows.forEach { row ->
                        PendingOpLine(row = row, onDiscard = { discardCandidate = row })
                    }
                }
                Spacer(Modifier.height(12.dp))
                SyncDetails(
                    lastContactMs = lastContactMs,
                    lastPushMs = lastPushMs,
                    server = serverInfo,
                )
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

    discardCandidate?.let { row ->
        AlertDialog(
            onDismissRequest = { discardCandidate = null },
            title = { Text(stringResource(R.string.sync_discard_title)) },
            text = { Text(stringResource(R.string.sync_discard_body, pendingOpLabel(row))) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.discardPendingOp(row.clientOpId)
                    discardCandidate = null
                }) { Text(stringResource(R.string.sync_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { discardCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * What Réglages > Infos shows, plus when the server last answered and when
 * it last took our changes: the dialog is not empty when all is well.
 */
@Composable
private fun SyncDetails(
    lastContactMs: Long?,
    lastPushMs: Long?,
    server: ConnectionViewModel.ServerInfo?,
) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val never = stringResource(R.string.sync_details_never)
    val unknown = stringResource(R.string.sync_details_unknown)
    Text(
        text = stringResource(R.string.sync_details_title),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(4.dp))
    DetailLine(R.string.sync_details_last_contact, lastContactMs?.let { fmt.format(Date(it)) } ?: never)
    DetailLine(R.string.sync_details_last_push, lastPushMs?.let { fmt.format(Date(it)) } ?: never)
    DetailLine(
        R.string.sync_details_app,
        stringResource(
            R.string.sync_details_app_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        ),
    )
    if (BuildConfig.BUILD_INFO.isNotEmpty()) {
        DetailLine(R.string.settings_infos_build, BuildConfig.BUILD_INFO)
    }
    DetailLine(R.string.sync_details_server_version, server?.version ?: unknown)
    DetailLine(R.string.sync_details_server_fingerprint, server?.fingerprint ?: unknown)
    DetailLine(
        R.string.sync_details_server_status,
        if (server == null) {
            stringResource(R.string.settings_infos_backend_offline)
        } else {
            stringResource(
                R.string.sync_details_server_status_format,
                server.status ?: unknown,
                server.database ?: unknown,
            )
        },
    )
}

@Composable
private fun DetailLine(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** "Fait : Changer les serviettes (Bob)" — what the op is about. */
@Composable
private fun pendingOpLabel(row: PendingOpRow): String {
    val kind = stringResource(
        when (row.kind) {
            PendingOpKind.TASK_CREATE -> R.string.sync_op_task_create
            PendingOpKind.TASK_EDIT -> R.string.sync_op_task_edit
            PendingOpKind.TASK_DELETE -> R.string.sync_op_task_delete
            PendingOpKind.COMPLETION_ADD -> R.string.sync_op_completion_add
            PendingOpKind.COMPLETION_EDIT -> R.string.sync_op_completion_edit
            PendingOpKind.COMPLETION_DELETE -> R.string.sync_op_completion_delete
            PendingOpKind.FREQUENCY_VOTE -> R.string.sync_op_frequency_vote
            PendingOpKind.OTHER -> R.string.sync_op_other
        }
    )
    val title = row.taskTitle ?: stringResource(R.string.sync_op_unknown_task)
    return if (row.memberName != null) {
        stringResource(R.string.sync_op_label_member_format, kind, title, row.memberName)
    } else {
        stringResource(R.string.sync_op_label_format, kind, title)
    }
}

/**
 * One queued op. The discard button only shows once the op has failed: an op
 * that has not been tried yet will leave on its own, there is nothing to undo.
 */
@Composable
private fun PendingOpLine(row: PendingOpRow, onDiscard: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(text = pendingOpLabel(row), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (row.attemptCount > 0) {
                    pluralStringResource(
                        R.plurals.sync_op_meta_failed,
                        row.attemptCount,
                        fmt.format(Date(row.queuedAtMs)),
                        row.attemptCount,
                    )
                } else {
                    stringResource(R.string.sync_op_meta_waiting, fmt.format(Date(row.queuedAtMs)))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (row.attemptCount > 0) {
            IconButton(onClick = onDiscard) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.sync_op_discard_cd),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

package com.torchons.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.core.clipboard.copySensitiveToClipboard
import com.torchons.app.R

/**
 * Donation page, opened via Intent.ACTION_VIEW. Comes from BuildConfig
 * (`ts.donate.url` in local.properties); empty = the button is hidden.
 */
private val DONATE_URL: String = com.torchons.app.BuildConfig.DONATE_URL

/** Email de contact du dev pour le bouton "M'ecrire". Source = BuildConfig
 *  (cf. build.gradle.kts > `tsSupportEmail`). Configurable via
 *  `ts.support.email=...` dans `local.properties` (gitignored) pour ne
 *  pas exposer un email perso dans le repo public. */
private val SUPPORT_EMAIL: String = com.torchons.app.BuildConfig.SUPPORT_EMAIL

/**
 * Card "Securite du foyer" affichant 2 actions critiques (v0.30) :
 *  - Regenerer mon code de recuperation : remplace l'ancien hash, affiche
 *    le nouveau code une seule fois avec Copier/Partager.
 *  - Quitter le foyer : self-leave avec confirm. Si l'user etait dans
 *    plusieurs foyers, bascule sur le suivant ; sinon logout complet.
 *
 * @param onLoggedOut callback declenche quand l'user a quitte son seul
 *   foyer et qu'il faut le rediriger vers Welcome.
 */
@Composable
fun SecuritySection(
    viewModel: SettingsViewModel,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val regenerateState by viewModel.regenerateState.collectAsState()
    val leaveState by viewModel.leaveState.collectAsState()
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(leaveState) {
        if (leaveState is SettingsViewModel.LeaveState.LoggedOut) {
            onLoggedOut()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.security_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            // --- Regenerer code ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.foyer_recovery_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.foyer_recovery_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showRegenerateConfirm = true }) {
                    Text(stringResource(R.string.foyer_recovery_button), style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Quitter le foyer ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.foyer_leave_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.foyer_leave_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { showLeaveConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.foyer_leave_button), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // --- Dialog : confirmer la regeneration ---
    if (showRegenerateConfirm && regenerateState is SettingsViewModel.RegenerateState.Idle) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text(stringResource(R.string.recovery_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.recovery_dialog_confirm_message))
            },
            confirmButton = {
                Button(onClick = {
                    showRegenerateConfirm = false
                    viewModel.regenerateRecoveryCode()
                }) {
                    Text(stringResource(R.string.foyer_recovery_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // --- Dialog : loading regeneration ---
    if (regenerateState is SettingsViewModel.RegenerateState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recovery_dialog_loading_title)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    // --- Dialog : afficher le nouveau code ---
    val regenSuccess = regenerateState as? SettingsViewModel.RegenerateState.Success
    if (regenSuccess != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text(stringResource(R.string.recovery_dialog_success_title)) },
            text = {
                Column {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = regenSuccess.recoveryCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
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
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
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
            text = { Text(regenError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    // --- Dialog : confirmer le leave ---
    if (showLeaveConfirm && leaveState is SettingsViewModel.LeaveState.Idle) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.leave_dialog_confirm_title)) },
            text = {
                Text(stringResource(R.string.leave_dialog_confirm_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveConfirm = false
                        viewModel.leaveFoyer()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.leave_dialog_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
                    CircularProgressIndicator()
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
            text = { Text(leaveError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/**
 * Card "Soutenir / Discuter avec le dev" (v0.30, narratif solo-dev).
 *
 * Deux boutons :
 *  - Payer un cafe au dev -> ouvre BMC dans le navigateur
 *  - M'ecrire -> ouvre l'app mail avec un brouillon vers SUPPORT_EMAIL
 */
@Composable
fun SupportDevCard() {
    val context = LocalContext.current
    val privacyUrl = stringResource(R.string.privacy_policy_url)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.support_dev_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.support_dev_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (DONATE_URL.isNotBlank()) {
                    OutlinedButton(
                        onClick = { openUrl(context, DONATE_URL) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Coffee, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.support_dev_bmc_button))
                    }
                }
                OutlinedButton(
                    onClick = { sendSupportEmail(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.support_dev_email_button))
                }
            }
            TextButton(
                onClick = { openUrl(context, privacyUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_privacy_policy))
            }
        }
    }
}

internal fun copyCode(context: Context, code: String) {
    copySensitiveToClipboard(context, context.getString(R.string.recovery_code_clipboard_label), code)
}

internal fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.recovery_share_subject))
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(R.string.recovery_share_body_format, code),
        )
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.recovery_share_chooser)),
    )
}

internal fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
}

private fun sendSupportEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$SUPPORT_EMAIL")
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.support_email_subject))
    }
    runCatching { context.startActivity(intent) }
}

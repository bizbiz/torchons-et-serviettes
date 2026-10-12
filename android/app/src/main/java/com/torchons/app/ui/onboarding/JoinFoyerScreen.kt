package com.torchons.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode

/**
 * Ecran "Rejoindre un foyer" (v0.30, Phase B4).
 *
 * 3 phases visuelles :
 *  1. Pas de scan -> bouton "Scanner le QR code" qui lance Quickie.
 *  2. Scan reussi + saisie display_name -> bouton "Rejoindre".
 *  3. Apres /pairing/join reussi -> ecran recovery code identique au
 *     wizard create (StepRecovery reutilise).
 *
 * Cf. docs/design-onboarding-public.md §3 (Owner affiche QR, joiner scanne).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinFoyerScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: JoinFoyerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val scanLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                viewModel.setInviteToken(result.content.rawValue ?: "")
            }
            is QRResult.QRError -> {
                /* erreur silencieuse pour l'instant — l'user peut re-tap */
            }
            QRResult.QRMissingPermission -> { /* l'OS gere le rationale */ }
            QRResult.QRUserCanceled -> { /* no-op */ }
        }
    }

    LaunchedEffect(state.done) { if (state.done) onSuccess() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.welcome_join_foyer)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            when {
                state.recoveryCode != null -> JoinSuccessRecovery(
                    foyerName = state.foyerName,
                    recoveryCode = state.recoveryCode!!,
                    onContinue = viewModel::finish,
                )

                state.inviteToken == null -> ScanInvitation(
                    onScanClick = { scanLauncher.launch(null) },
                    onCodePasted = viewModel::setInviteToken,
                )

                state.kind == "add_device" -> AddingDevice(
                    foyerName = state.foyerName,
                )

                else -> EnterDisplayName(
                    displayName = state.displayName,
                    onChange = viewModel::setDisplayName,
                    isSubmitting = state.isSubmitting,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }
}

@Composable
private fun ScanInvitation(onScanClick: () -> Unit, onCodePasted: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.join_scan_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.join_scan_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onScanClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.join_scan_button))
    }
    Spacer(Modifier.height(32.dp))
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it; invalid = false },
        label = { Text(stringResource(R.string.join_paste_label)) },
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.join_paste_invalid)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            val token = extractInviteToken(pasted)
            if (token == null) invalid = true else onCodePasted(token)
        },
        enabled = pasted.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.join_paste_button)) }
}

/**
 * Finds the invite token (32 hex chars, `secrets.token_hex(16)` server side)
 * in whatever was pasted: the bare code, or the whole shared message.
 */
internal fun extractInviteToken(text: String): String? =
    Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])").find(text)?.value?.lowercase()

@Composable
private fun EnterDisplayName(
    displayName: String,
    onChange: (String) -> Unit,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_your_name_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.onboarding_your_name_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.onboarding_your_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onSubmit,
        enabled = !isSubmitting && displayName.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_connecting))
        } else {
            Text(stringResource(R.string.join_submit_button))
        }
    }
}

@Composable
private fun AddingDevice(foyerName: String?) {
    Text(
        text = stringResource(R.string.join_adding_device_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (foyerName != null)
            stringResource(R.string.join_adding_device_with_foyer_format, foyerName)
        else
            stringResource(R.string.join_adding_device_no_foyer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun JoinSuccessRecovery(
    foyerName: String?,
    recoveryCode: String,
    onContinue: () -> Unit,
) {
    Text(
        text = if (foyerName != null) stringResource(R.string.join_welcome_format, foyerName)
        else stringResource(R.string.join_welcome_fallback),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.join_recovery_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = recoveryCode,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.recovery_warning_dont_share),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }

    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reuse les helpers du wizard pour Copier/Partager via les
        // boutons compactes. Les fonctions sont package-private.
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.OutlinedButton(
            onClick = { copyToClipboardJoin(context, recoveryCode) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_dialog_copy))
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { shareCodeJoin(context, recoveryCode) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_dialog_share))
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_recovery_continue)) }
}

private fun copyToClipboardJoin(
    context: android.content.Context,
    code: String,
) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(
        android.content.ClipData.newPlainText(
            context.getString(R.string.recovery_code_clipboard_label),
            code,
        ),
    )
}

private fun shareCodeJoin(
    context: android.content.Context,
    code: String,
) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            android.content.Intent.EXTRA_SUBJECT,
            context.getString(R.string.recovery_share_subject),
        )
        putExtra(
            android.content.Intent.EXTRA_TEXT,
            context.getString(R.string.recovery_share_body_format, code),
        )
    }
    context.startActivity(
        android.content.Intent.createChooser(send, context.getString(R.string.recovery_share_chooser)),
    )
}

package com.torchons.app.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.core.clipboard.copySensitiveToClipboard

@Composable
internal fun StepRecovery(
    recoveryCode: String?,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.wizard_recovery_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_recovery_help),
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
            text = recoveryCode ?: stringResource(R.string.wizard_recovery_placeholder),
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
            text = stringResource(R.string.recovery_warning_dont_share_long),
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
        OutlinedButton(
            onClick = {
                recoveryCode?.let { copyToClipboard(context, it) }
            },
            modifier = Modifier.weight(1f),
            enabled = recoveryCode != null,
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_dialog_copy))
        }
        OutlinedButton(
            onClick = {
                recoveryCode?.let { shareCode(context, it) }
            },
            modifier = Modifier.weight(1f),
            enabled = recoveryCode != null,
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
        enabled = recoveryCode != null,
    ) { Text(stringResource(R.string.wizard_recovery_continue)) }
}

private fun copyToClipboard(context: Context, code: String) {
    copySensitiveToClipboard(context, context.getString(R.string.recovery_code_clipboard_label), code)
}

private fun shareCode(context: Context, code: String) {
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

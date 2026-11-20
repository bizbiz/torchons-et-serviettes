package com.torchons.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torchons.app.R

/**
 * What a screen shows when an online-only tap failed and its change was rolled back: the translated
 * message, plus the "Send to Quentin" button when the case calls for it. Nothing while [error] is null.
 * [screen] names the screen in the mail draft.
 */
@Composable
fun ActionErrorDialog(error: UserError?, screen: String, onDismiss: () -> Unit) {
    if (error == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_error_title)) },
        text = { UserErrorDialogText(error, screen) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

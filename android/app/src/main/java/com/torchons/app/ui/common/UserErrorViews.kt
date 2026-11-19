package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import com.torchons.app.R

/**
 * Error shown in the layout (text under a field, card, dialog body): the
 * message, plus the "Send to Quentin" button when the case calls for it.
 * [screen] names the screen in the mail draft.
 */
@Composable
fun UserErrorBlock(
    error: UserError,
    screen: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.error,
    textAlign: TextAlign? = null,
) {
    Column(modifier = modifier) {
        Text(text = error.message, style = style, color = color, textAlign = textAlign)
        UserErrorReportButton(error, screen)
    }
}

/** [UserErrorBlock] for the body of an AlertDialog: keeps the dialog's own text colour. */
@Composable
fun UserErrorDialogText(error: UserError, screen: String) =
    UserErrorBlock(error, screen, color = LocalContentColor.current)

/** The "Send to Quentin" button alone, for screens that lay the message out themselves. */
@Composable
fun UserErrorReportButton(error: UserError, screen: String) {
    if (!error.reportable) return
    val context = LocalContext.current
    TextButton(onClick = { sendErrorReport(context, error, screen) }) {
        Text(stringResource(R.string.user_error_send_to_quentin))
    }
}

/**
 * Error shown as a snackbar: the same message, with "Send to Quentin" as
 * the snackbar action when the case calls for it.
 */
suspend fun SnackbarHostState.showUserError(
    context: android.content.Context,
    error: UserError,
    screen: String,
) {
    val result = showSnackbar(
        message = error.message,
        actionLabel = if (error.reportable) context.getString(R.string.user_error_send_to_quentin) else null,
        duration = if (error.reportable) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) sendErrorReport(context, error, screen)
}

package com.torchons.app.ui.common

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Snackbar host that stays above the soft keyboard.
 *
 * The activity is edge-to-edge and Scaffold does not consume the IME insets, so a plain
 * [SnackbarHost] is laid out behind the keyboard while it is open. Every screen uses this one.
 */
@Composable
fun ImeSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state, modifier = modifier.imePadding())
}

/**
 * Returns a function that hides the keyboard and drops the focus of the text field.
 * Call it when a form is validated (submit button, IME action), never on a plain newline.
 */
@Composable
fun rememberKeyboardDismisser(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return {
        keyboard?.hide()
        focus.clearFocus()
    }
}

/**
 * Wraps a submit callback so that it first closes the keyboard.
 * Use for the onClick of a form button: `onClick = dismissKeyboardThen(onSubmit)`.
 */
@Composable
fun dismissKeyboardThen(action: () -> Unit): () -> Unit {
    val dismiss = rememberKeyboardDismisser()
    return {
        dismiss()
        action()
    }
}

/**
 * Keyboard actions for a field whose IME key validates the form (Done / Go / Send / Search):
 * closes the keyboard, then runs [onSubmit]. Pass the same ImeAction in `KeyboardOptions`.
 */
@Composable
fun submitKeyboardActions(onSubmit: () -> Unit = {}): KeyboardActions {
    val action = dismissKeyboardThen(onSubmit)
    return KeyboardActions(
        onDone = { action() },
        onGo = { action() },
        onSend = { action() },
        onSearch = { action() },
    )
}

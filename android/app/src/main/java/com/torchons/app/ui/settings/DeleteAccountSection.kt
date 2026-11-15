package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response

/** Where the "delete my account" call stands. Idle -> Deleting -> Done, or back to Failed. */
sealed class DeleteAccountState {
    data object Idle : DeleteAccountState()
    data object Deleting : DeleteAccountState()
    data object Failed : DeleteAccountState()
    data object Done : DeleteAccountState()
}

/**
 * Account deletion (`DELETE /api/me`). Not offline-first: nothing is queued, and nothing is
 * erased locally unless the server answered 2xx. On success the local wipe is the one already
 * used when a device is revoked (injected as [wipe], backed by `DeviceRevocationMonitor.wipeNow`).
 *
 * `internal` because SettingsViewModel is its only user.
 */
internal class DeleteAccountAction(
    private val scope: CoroutineScope,
    private val deleteMe: suspend () -> Response<Unit>,
    private val wipe: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<DeleteAccountState>(DeleteAccountState.Idle)
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    /** Ignored while a call is already running, so a double tap cannot send two requests. */
    fun start() {
        if (_state.value == DeleteAccountState.Deleting) return
        _state.value = DeleteAccountState.Deleting
        scope.launch { run() }
    }

    fun dismissError() {
        if (_state.value == DeleteAccountState.Failed) _state.value = DeleteAccountState.Idle
    }

    suspend fun run() {
        val ok = try {
            deleteMe().isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            _state.value = DeleteAccountState.Failed
            return
        }
        wipe()
        _state.value = DeleteAccountState.Done
    }
}

/**
 * Last section of the Reglages screen: "Supprimer mon compte" + confirmation dialog.
 * The privacy policy points users here; keep the label and the place in sync with it.
 *
 * @param onDeleted called once the account is deleted and the phone wiped (go to Welcome).
 */
@Composable
fun DeleteAccountSection(
    viewModel: SettingsViewModel,
    onDeleted: () -> Unit,
) {
    val state by viewModel.deleteAccountState.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state == DeleteAccountState.Done) onDeleted()
    }

    OutlinedButton(
        onClick = { showConfirm = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 32.dp)
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.delete_account_button))
    }

    if (showConfirm && state != DeleteAccountState.Done) {
        val deleting = state == DeleteAccountState.Deleting
        AlertDialog(
            onDismissRequest = {
                if (!deleting) {
                    showConfirm = false
                    viewModel.dismissDeleteAccountError()
                }
            },
            title = { Text(stringResource(R.string.delete_account_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_account_dialog_message))
                    if (state == DeleteAccountState.Failed) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.delete_account_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteAccount() },
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        Text(stringResource(R.string.delete_account_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        viewModel.dismissDeleteAccountError()
                    },
                    enabled = !deleting,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

package com.torchons.app.ui.onboarding

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R

/**
 * Ecran "Retrouver mon foyer" (v0.30, Phase B5).
 *
 * 2 phases :
 *  1. Saisie du code (8 chars XXXX-XXXX) -> /api/recovery/restore retourne
 *     un preview de l'identite + restore_token (TTL 5 min).
 *  2. Verification visuelle ("C'est bien toi, X (admin de Chez Y,
 *     N taches faites) ?") avec Oui/Non. Oui -> /restore/confirm
 *     emet le JWT, save auth, navigation MAIN.
 *
 * Cf. docs/design-onboarding-public.md §5 (Q5e du grill-me).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreFoyerScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: RestoreFoyerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.done) { if (state.done) onSuccess() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.welcome_restore_foyer)) },
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
            val preview = state.preview
            if (preview == null) {
                EnterCode(
                    code = state.code,
                    onChange = viewModel::setCode,
                    isSubmitting = state.isSubmitting,
                    onLookup = viewModel::lookup,
                )
            } else {
                ConfirmIdentity(
                    preview = preview,
                    isSubmitting = state.isSubmitting,
                    onConfirm = viewModel::confirm,
                    onCancel = viewModel::resetPreview,
                )
            }
        }
    }
}

@Composable
private fun EnterCode(
    code: String,
    onChange: (String) -> Unit,
    isSubmitting: Boolean,
    onLookup: () -> Unit,
) {
    Text(
        text = stringResource(R.string.restore_enter_code_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.restore_enter_code_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.restore_code_label)) },
        placeholder = { Text(stringResource(R.string.restore_code_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onLookup,
        enabled = !isSubmitting && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.restore_searching))
        } else {
            Text(stringResource(R.string.restore_verify_button))
        }
    }
}

@Composable
private fun ConfirmIdentity(
    preview: PreviewData,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(R.string.restore_confirm_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = preview.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (preview.foyerName != null) {
                Spacer(Modifier.height(4.dp))
                val roleLabel = when (preview.role) {
                    "admin" -> stringResource(R.string.restore_role_admin)
                    "former_member" -> stringResource(R.string.restore_role_former_member)
                    else -> stringResource(R.string.restore_role_member)
                }
                Text(
                    text = stringResource(R.string.restore_role_of_format, roleLabel, preview.foyerName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            val completionsLabel = pluralStringResource(
                R.plurals.restore_completions_count,
                preview.completionsCount,
                preview.completionsCount,
            )
            val ratingsLabel = pluralStringResource(
                R.plurals.restore_ratings_count,
                preview.ratingsCount,
                preview.ratingsCount,
            )
            Text(
                text = stringResource(R.string.restore_stats_join_format, completionsLabel, ratingsLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onConfirm,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.restore_restoring))
        } else {
            Text(stringResource(R.string.restore_yes_its_me))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onCancel,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.restore_no_not_me))
    }
}

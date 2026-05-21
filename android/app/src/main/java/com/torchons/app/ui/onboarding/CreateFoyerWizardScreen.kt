package com.torchons.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.data.model.CatalogRoomView
import com.torchons.app.data.model.CatalogTaskView

/**
 * Wizard "Creer un foyer" — 5 etapes (v0.30). Cf. design-onboarding-public.md §4.
 *
 * @param onSuccess appele quand l'user a termine l'etape 5 (recovery affichee
 *   et "Continuer" cliquee). L'appelant doit naviguer vers MAIN.
 * @param onBack appele si l'user fait back depuis l'etape 1 (sortie du wizard
 *   sans avoir cree le foyer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFoyerWizardScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit,
    /** P4a.2 — true = user déjà connecté ajoutant un foyer : 3 étapes
     *  (Foyer/Pièces/Tâches), pas de Toi ni de Recovery. */
    addFoyerMode: Boolean = false,
    viewModel: CreateFoyerWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(addFoyerMode) { if (addFoyerMode) viewModel.enableAddFoyerMode() }

    // En addFoyer : étapes internes 2..4 affichées 1/3..3/3.
    val totalSteps = if (state.addFoyerMode) 3 else 5
    val shownStep = if (state.addFoyerMode)
        (state.step - 1).coerceIn(1, totalSteps) else state.step.coerceAtMost(totalSteps)
    val firstStep = if (state.addFoyerMode) 2 else 1

    LaunchedEffect(state.done) { if (state.done) onSuccess() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_title_format, shownStep, totalSteps)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step <= firstStep) onBack()
                            else viewModel.prevStep()
                        },
                    ) {
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
                .padding(paddingValues),
        ) {
            LinearProgressIndicator(
                progress = { shownStep / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                when (state.step) {
                    1 -> StepToi(
                        value = state.displayName,
                        onChange = viewModel::setDisplayName,
                        onNext = viewModel::nextStep,
                    )

                    2 -> StepFoyer(
                        value = state.foyerName,
                        defaultHint = if (state.displayName.isNotBlank())
                            stringResource(R.string.wizard_foyer_default_at_someones_format, state.displayName)
                        else stringResource(R.string.settings_foyer_default_name),
                        onChange = viewModel::setFoyerName,
                        onNext = viewModel::nextStep,
                    )

                    3 -> StepPieces(
                        rooms = state.rooms,
                        selectedKeys = state.selectedRoomKeys,
                        customRooms = state.customRooms,
                        catalogStatus = state.catalogStatus,
                        onToggle = viewModel::toggleRoom,
                        onAddCustomRoom = viewModel::addCustomRoom,
                        onRemoveCustomRoom = viewModel::removeCustomRoom,
                        onRetry = viewModel::retryCatalog,
                        onNext = viewModel::nextStep,
                    )

                    4 -> StepTaches(
                        tasks = viewModel.visibleTasks(state),
                        selectedKeys = state.selectedTaskKeys,
                        isSubmitting = state.isSubmitting,
                        catalogStatus = state.catalogStatus,
                        onToggle = viewModel::toggleTask,
                        onRetry = viewModel::retryCatalog,
                        onNext = viewModel::nextStep,
                    )

                    5 -> StepRecovery(
                        recoveryCode = state.recoveryCode,
                        onContinue = viewModel::finish,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepToi(
    value: String,
    onChange: (String) -> Unit,
    onNext: () -> Unit,
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
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.onboarding_your_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

@Composable
private fun StepFoyer(
    value: String,
    defaultHint: String,
    onChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.wizard_foyer_name_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_foyer_name_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.foyer_name_label)) },
        placeholder = { Text(defaultHint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepPieces(
    rooms: List<CatalogRoomView>,
    selectedKeys: Set<String>,
    customRooms: List<String>,
    catalogStatus: CatalogStatus,
    onToggle: (String) -> Unit,
    onAddCustomRoom: (String) -> Unit,
    onRemoveCustomRoom: (String) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.wizard_rooms_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_rooms_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    when (catalogStatus) {
        CatalogStatus.LOADING -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        CatalogStatus.ERROR -> CatalogErrorBox(onRetry = onRetry)

        CatalogStatus.OK -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rooms.forEach { room ->
                FilterChip(
                    selected = room.key in selectedKeys,
                    onClick = { onToggle(room.key) },
                    label = {
                        Text(
                            text = if (room.emoji != null) "${room.emoji} ${room.name}" else room.name,
                        )
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.wizard_rooms_add_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    var newRoom by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newRoom,
            onValueChange = { newRoom = it },
            label = { Text(stringResource(R.string.wizard_rooms_custom_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAddCustomRoom(newRoom)
                newRoom = ""
            },
            enabled = newRoom.isNotBlank(),
        ) { Text(stringResource(R.string.common_add)) }
    }
    if (customRooms.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            customRooms.forEach { name ->
                FilterChip(
                    selected = true,
                    onClick = { onRemoveCustomRoom(name) },
                    label = { Text(stringResource(R.string.wizard_rooms_custom_chip_format, name)) },
                )
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.wizard_continue)) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepTaches(
    tasks: List<CatalogTaskView>,
    selectedKeys: Set<String>,
    isSubmitting: Boolean,
    catalogStatus: CatalogStatus,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.wizard_tasks_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_tasks_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    when (catalogStatus) {
        CatalogStatus.LOADING -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        CatalogStatus.ERROR -> CatalogErrorBox(onRetry = onRetry)

        CatalogStatus.OK -> if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.wizard_tasks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEach { task ->
                    FilterChip(
                        selected = task.key in selectedKeys,
                        onClick = { onToggle(task.key) },
                        label = { Text(task.title) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
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
            Text(stringResource(R.string.wizard_tasks_creating))
        } else {
            Text(stringResource(R.string.wizard_tasks_submit))
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onNext,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.wizard_skip_step))
    }
}

/** Encart d'erreur reseau partage (steps Pieces/Taches) : message clair +
 *  bouton Reessayer. L'user peut aussi continuer sans (skip) ou ajouter des
 *  pieces custom — pas de spinner infini. */
@Composable
private fun CatalogErrorBox(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.wizard_catalog_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.wizard_retry)) }
        }
    }
}

@Composable
private fun StepRecovery(
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
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    cm.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.recovery_code_clipboard_label), code),
    )
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

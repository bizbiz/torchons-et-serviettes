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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    viewModel: CreateFoyerWizardViewModel = hiltViewModel(),
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
                title = { Text("Creer un foyer · etape ${state.step.coerceAtMost(5)}/5") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step <= 1) onBack() else viewModel.prevStep()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
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
                progress = { state.step / 5f },
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
                            "Chez ${state.displayName}" else "Mon foyer",
                        onChange = viewModel::setFoyerName,
                        onNext = viewModel::nextStep,
                    )

                    3 -> StepPieces(
                        rooms = state.rooms,
                        selectedKeys = state.selectedRoomKeys,
                        onToggle = viewModel::toggleRoom,
                        onNext = viewModel::nextStep,
                    )

                    4 -> StepTaches(
                        tasks = viewModel.visibleTasks(state),
                        selectedKeys = state.selectedTaskKeys,
                        isSubmitting = state.isSubmitting,
                        onToggle = viewModel::toggleTask,
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
        text = "Comment tu t'appelles ?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Ton prenom (ou pseudo) — visible par les autres membres du foyer.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Ton prenom") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continuer") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Sauter cette etape")
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
        text = "Comment s'appelle ton foyer ?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Le nom de ta maison ou de ta colloc, pour t'y retrouver si tu en gere plusieurs.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Nom du foyer") },
        placeholder = { Text(defaultHint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continuer") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Sauter cette etape")
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepPieces(
    rooms: List<CatalogRoomView>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = "Quelles pieces tu as ?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Selectionne les pieces qui correspondent a ton chez-toi. Ca permet de proposer les bonnes taches a l'etape suivante.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (rooms.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        FlowRow(
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

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continuer") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Sauter cette etape")
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepTaches(
    tasks: List<CatalogTaskView>,
    selectedKeys: Set<String>,
    isSubmitting: Boolean,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = "Quelles taches tu veux suivre ?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Coche celles qui te concernent. Tu pourras toujours en ajouter ou en retirer plus tard.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (tasks.isEmpty()) {
        Text(
            text = "Aucune tache disponible pour les pieces selectionnees. Tu pourras les ajouter manuellement plus tard.",
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
            Text("Creation...")
        } else {
            Text("Creer mon foyer")
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onNext,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Sauter cette etape")
    }
}

@Composable
private fun StepRecovery(
    recoveryCode: String?,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current

    Text(
        text = "Voici ton code de recuperation",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Il te permet de retrouver tes droits d'admin sur ton foyer si tu perds ton telephone. Note-le bien, il ne sera affiche qu'une seule fois.",
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
            text = recoveryCode ?: "...",
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
            text = "⚠ Ne le partage avec personne d'autre que toi-meme. Toute personne qui possede ce code peut prendre le controle de ton foyer.",
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
            Text("Copier")
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
            Text("Partager")
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        enabled = recoveryCode != null,
    ) { Text("J'ai note mon code, continuer") }
}

private fun copyToClipboard(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Code de recuperation T&S", code))
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_SUBJECT,
            "Code de recuperation Torchons & Serviettes",
        )
        putExtra(
            Intent.EXTRA_TEXT,
            "Voici mon code de recuperation pour mon foyer dans l'app Torchons & Serviettes : $code\n\n" +
                "Garde-le precieusement, il me permet de retrouver mes droits d'admin si je perds mon telephone.",
        )
    }
    context.startActivity(Intent.createChooser(send, "Partager le code"))
}

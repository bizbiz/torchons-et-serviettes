package com.torchons.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * URL Buy Me a Coffee du dev. Quand le user clique sur "Payer un cafe",
 * on l'envoie sur cette page externe via Intent.ACTION_VIEW. A remplacer
 * par l'URL reelle quand le compte BMC sera cree (cf. Phase F roadmap dev).
 */
private const val BMC_URL = "https://buymeacoffee.com/<account>"

/** Email de contact du dev pour le bouton "M'ecrire". */
private const val SUPPORT_EMAIL = "contact@torchonsetserviettes.app"

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
                text = "Securite du foyer",
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
                        text = "Regenerer mon code de recuperation",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Te permet de retrouver ton foyer si tu perds ton tel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showRegenerateConfirm = true }) {
                    Text("Generer", style = MaterialTheme.typography.labelSmall)
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
                        text = "Quitter le foyer",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Tu ne verras plus les taches de ce foyer.",
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
                    Text("Quitter", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // --- Dialog : confirmer la regeneration ---
    if (showRegenerateConfirm && regenerateState is SettingsViewModel.RegenerateState.Idle) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("Regenerer ton code ?") },
            text = {
                Text(
                    "Cela invalide ton ancien code de recuperation s'il existait. " +
                        "Tu recevras un nouveau code, a noter immediatement — il " +
                        "ne sera affiche qu'une seule fois.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRegenerateConfirm = false
                    viewModel.regenerateRecoveryCode()
                }) {
                    Text("Generer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    // --- Dialog : loading regeneration ---
    if (regenerateState is SettingsViewModel.RegenerateState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Generation...") },
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
            title = { Text("Voici ton nouveau code") },
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
                        text = "⚠ Note-le maintenant. Il ne sera plus jamais affiche.",
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
                            Text("Copier")
                        }
                        OutlinedButton(
                            onClick = { shareCode(context, regenSuccess.recoveryCode) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Partager")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) {
                    Text("J'ai note")
                }
            },
        )
    }

    val regenError = regenerateState as? SettingsViewModel.RegenerateState.Error
    if (regenError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRegenerate() },
            title = { Text("Erreur") },
            text = { Text(regenError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRegenerate() }) { Text("OK") }
            },
        )
    }

    // --- Dialog : confirmer le leave ---
    if (showLeaveConfirm && leaveState is SettingsViewModel.LeaveState.Idle) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Quitter ce foyer ?") },
            text = {
                Text(
                    "Tu ne verras plus les taches, votes, completions de ce foyer. " +
                        "Tu peux le rejoindre a nouveau plus tard via un QR ou ton " +
                        "code de recuperation.",
                )
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
                    Text("Quitter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text("Annuler")
                }
            },
        )
    }

    if (leaveState is SettingsViewModel.LeaveState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Sortie en cours...") },
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
            title = { Text("C'est fait") },
            text = { Text("Tu as bascule sur un autre foyer ou tu es membre.") },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text("OK") }
            },
        )
    }

    val leaveError = leaveState as? SettingsViewModel.LeaveState.Error
    if (leaveError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeave() },
            title = { Text("Erreur") },
            text = { Text(leaveError.message) },
            confirmButton = {
                Button(onClick = { viewModel.dismissLeave() }) { Text("OK") }
            },
        )
    }
}

/**
 * Card "Soutenir / Discuter avec le dev" (v0.30, narratif solo-dev).
 *
 * Cf. docs/design-onboarding-public.md §8. Deux boutons :
 *  - Payer un cafe au dev -> ouvre BMC dans le navigateur
 *  - M'ecrire -> ouvre l'app mail avec un brouillon vers SUPPORT_EMAIL
 */
@Composable
fun SupportDevCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Soutenir / Discuter avec le dev",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Salut, je suis Quentin, dev solo. Je fais cette app sur " +
                    "mon temps libre. Le serveur + le domaine me coutent ~10€/mois. " +
                    "Si l'app te plait, ou si tu as une idee, un bug, une remarque, " +
                    "fais-moi signe !",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, BMC_URL) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Coffee, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Payer un cafe")
                }
                OutlinedButton(
                    onClick = { sendSupportEmail(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("M'ecrire")
                }
            }
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Code de recuperation T&S", code))
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Code de recuperation Torchons & Serviettes")
        putExtra(
            Intent.EXTRA_TEXT,
            "Voici mon code de recuperation pour mon foyer dans l'app Torchons & " +
                "Serviettes : $code\n\nGarde-le precieusement.",
        )
    }
    context.startActivity(Intent.createChooser(send, "Partager le code"))
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
}

private fun sendSupportEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$SUPPORT_EMAIL")
        putExtra(Intent.EXTRA_SUBJECT, "Torchons & Serviettes — feedback")
    }
    runCatching { context.startActivity(intent) }
}

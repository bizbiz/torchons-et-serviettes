package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torchons.app.R
import com.torchons.app.data.model.PropositionView
import com.torchons.app.util.TaskUrgencyCalculator
import kotlinx.coroutines.delay

/**
 * Modal non-dismissable cote receveur quand une proposition arrive
 * (v0.8.1 Feature 3). L'user doit cliquer Accept ou Refuse, ou attendre
 * le timeout. Pas de tap-outside-to-dismiss.
 */
@Composable
fun PropositionReceiverDialog(
    proposition: PropositionView,
    currentUserId: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = stringResource(R.string.proposition_receiver_title_format, proposition.proposerName),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.proposition_receiver_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val youSuffix = stringResource(R.string.proposition_receiver_you_suffix)
                proposition.responses.forEach { r ->
                    val mins = proposition.sliderValues[r.userId] ?: 0
                    val isMe = r.userId == currentUserId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = r.displayName + if (isMe) youSuffix else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.proposition_member_value_format, mins),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.proposition_receiver_timeout_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = TaskUrgencyCalculator.VERT),
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.proposition_accept))
            }
        },
        dismissButton = {
            Button(
                onClick = onDecline,
                colors = ButtonDefaults.buttonColors(containerColor = TaskUrgencyCalculator.ROUGE),
            ) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.proposition_decline))
            }
        },
    )
}

/**
 * Ecran d'attente bloquant cote proposer. Liste les users du foyer avec
 * leur statut (pending / accepted / declined) et un spinner. Non-dismissable
 * par tap-outside : le proposer doit attendre que tout le monde reponde,
 * que ca expire, ou cliquer explicitement "Annuler".
 */
@Composable
fun PropositionProposerWaitingDialog(
    proposition: PropositionView,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable par tap-outside */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.proposition_waiting_title),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.proposition_waiting_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                proposition.responses.forEach { r ->
                    val mins = proposition.sliderValues[r.userId] ?: 0
                    val (icon, color) = when (r.status) {
                        "accepted" -> Icons.Default.Check to TaskUrgencyCalculator.VERT
                        "declined" -> Icons.Default.Close to TaskUrgencyCalculator.ROUGE
                        else -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = r.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.proposition_member_value_format, mins),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.proposition_waiting_expire),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.proposition_waiting_cancel))
            }
        },
    )
}

/**
 * Popup de celebration affichee a tous les users du foyer quand une
 * proposition est acceptee par tout le monde. Contient :
 * - Konfetti (reutilise la lib konfetti-compose deja en place)
 * - Texte "Vous etes sur la bonne voie vers un foyer propre"
 * - Emoji fleche "vers quoi ?" qui reveal un easter egg emoji (v0.8.1)
 *   v0.8.2 : le vrai gif chambre-en-bordel.
 */
@Composable
fun PropositionCelebrationDialog(
    proposition: PropositionView,
    currentUserId: String?,
    onDismiss: () -> Unit,
) {
    var showEasterEgg by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = stringResource(R.string.proposition_celebration_title),
                fontWeight = FontWeight.Bold,
                color = TaskUrgencyCalculator.VERT,
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.proposition_celebration_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val youSuffix = stringResource(R.string.proposition_receiver_you_suffix)
                proposition.responses.forEach { r ->
                    val mins = proposition.sliderValues[r.userId] ?: 0
                    val isMe = r.userId == currentUserId
                    Text(
                        text = stringResource(
                            R.string.proposition_celebration_member_format,
                            r.displayName,
                            if (isMe) youSuffix else "",
                            mins,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.proposition_celebration_punchline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TaskUrgencyCalculator.VERT,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showEasterEgg = !showEasterEgg }) {
                    Text(
                        if (showEasterEgg) stringResource(R.string.proposition_celebration_easter_close)
                        else stringResource(R.string.proposition_celebration_easter_open)
                    )
                }
                if (showEasterEgg) {
                    // v0.8.1 : emoji easter egg. v0.8.2 : vrai gif bundle.
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "\uD83C\uDF2A\uFE0F\uD83D\uDCA8\uD83E\uDDF9\uD83D\uDDD1\uFE0F",
                                style = MaterialTheme.typography.displayLarge,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.proposition_celebration_chaos_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.proposition_celebration_chaos_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.proposition_celebration_lets_go)) }
        },
    )
}

package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/** Ligne de notation affinite per-user, telle qu'exposee par GET /ratings. */
data class AffinityRatingRow(
    val userId: String,
    val displayName: String,
    val affinity: Int?,
)

/**
 * Modal "+ d'info" sur l'affinite d'une tache (v0.28).
 *
 * Decisions /grill-me Q11 :
 * - Pedago courte qui rappelle que l'affinite est PERSO (n'influence que
 *   les suggestions du user, pas celles des autres). L'autre membre du
 *   foyer comprend pourquoi on ne peut pas demander a quelqu'un de
 *   revoter son affinite.
 * - Liste des affinites par user (etoiles 1-10 -> texte resume).
 * - Bouton "Modifier mon affinite" -> ouvre le RatingDialog en mode
 *   affinite seule (parent gere l'ouverture).
 * - PAS de bouton "Demander a X" (regle confirmee : c'est subjectif perso).
 */
@Composable
fun AffinityInfoDialog(
    ratings: List<AffinityRatingRow>,
    currentUserId: String?,
    onEditMyAffinity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val myRow = ratings.firstOrNull { it.userId == currentUserId }
    val otherRows = ratings.filter { it.userId != currentUserId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.affinity_info_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.affinity_info_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (myRow != null) {
                    AffinityRow(
                        name = stringResource(R.string.affinity_info_me_label),
                        score = myRow.affinity,
                        isMe = true,
                        actionButton = {
                            OutlinedButton(onClick = onEditMyAffinity) {
                                Text(
                                    stringResource(R.string.affinity_info_button_edit_my),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                }

                otherRows.forEach { other ->
                    AffinityRow(
                        name = other.displayName,
                        score = other.affinity,
                        isMe = false,
                        actionButton = null,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun AffinityRow(
    name: String,
    score: Int?,
    isMe: Boolean,
    actionButton: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = score?.let {
                    stringResource(R.string.affinity_info_row_format, it, affinityLabel(it))
                } ?: stringResource(R.string.affinity_info_not_rated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionButton != null) {
            Spacer(Modifier.width(8.dp))
            actionButton()
        }
    }
}

/** Resume textuel localise d'un score d'affinite 1..10. */
@Composable
private fun affinityLabel(score: Int): String = stringResource(
    when {
        score <= 2 -> R.string.affinity_label_hate
        score <= 4 -> R.string.affinity_label_meh
        score <= 6 -> R.string.affinity_label_neutral
        score <= 8 -> R.string.affinity_label_like
        else -> R.string.affinity_label_love
    }
)

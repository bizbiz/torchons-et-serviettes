package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Modal pedago "pourquoi 0 point ?" pour les taches solo (un seul assignee).
 *
 * Les points servent a comparer la performance entre membres du foyer : sur
 * une tache solo, il n'y a personne avec qui comparer, donc 0 point. La
 * tache reste utile pour mettre a jour son etat de retard (cycle freshness).
 *
 * Affiche depuis :
 *  - SuggestionCard de la home, derriere un icone "i" a cote de "0 pt"
 *  - CompletionDialog (cas PERSO), idem
 */
@Composable
fun SoloPointsInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pourquoi 0 point ?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Les points servent a comparer l'investissement de chaque membre du foyer (fair-share).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Cette tache n'a qu'un seul participant : il n'y a personne avec qui comparer, donc elle ne rapporte pas de points.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "La valider reste utile : ca remet le compteur de retard a zero et ca garde l'historique a jour. 👍",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Compris") }
        },
    )
}

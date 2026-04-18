package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.UserPrompt

/**
 * Dispatcher de modal pour la queue unifiee user_prompts. Affiche le bon
 * composable selon le type du prompt : BUMP_EFFORT ou VOTE_RATING.
 *
 * Pour VOTE_RATING, on renvoie vers le flow de notation existant (callback
 * onOpenRating). Pour BUMP_EFFORT, on affiche un modal dedie avec la liste
 * des completions recentes et un bouton +1 effort.
 *
 * @param onDismiss fermer le modal (resolution = DISMISSED)
 * @param onBumpEffort user accepte +1 effort (resolution = ANSWERED, action = "bump_effort")
 * @param onOpenRating ouvrir le popup de notation existant pour la tache
 */
@Composable
fun PromptModal(
    prompt: UserPrompt,
    onDismiss: () -> Unit,
    onBumpEffort: () -> Unit,
    onOpenRating: (taskId: String) -> Unit,
    onResolvePresence: (payload: Map<String, Any?>) -> Unit = {},
    onNavigateToFreezeSettings: () -> Unit = {},
) {
    when (prompt.type) {
        "BUMP_EFFORT" -> BumpEffortDialog(
            prompt = prompt,
            onDismiss = onDismiss,
            onBump = onBumpEffort,
            onEditRating = { onOpenRating(prompt.context["task_id"]?.toString().orEmpty()) },
        )
        "VOTE_RATING" -> {
            // Pour VOTE_RATING, on ouvre directement le flow de notation
            // existant et on resolve. Le caller (HomeViewModel) se charge
            // de resolve le prompt une fois la notation soumise.
            onOpenRating(prompt.context["task_id"]?.toString().orEmpty())
        }
        "RESOLVE_PRESENCE" -> ResolvePresenceDialog(
            prompt = prompt,
            onResolve = onResolvePresence,
            onDismiss = onDismiss,
            onNavigateToSettings = onNavigateToFreezeSettings,
        )
        else -> onDismiss()  // type inconnu — fermer
    }
}

/**
 * Modal dedie pour BUMP_EFFORT : cette tache est systematiquement en retard.
 *
 * Contenu :
 * - Titre + nom de la tache
 * - Liste des N dernieres completions (qui, quand, en retard ?)
 * - Stats : "X% des completions sont en zone bonus sur Y jours"
 * - Bouton "+1 effort" (action rapide)
 * - Bouton "Modifier les notes" (ouvre le popup notation)
 * - Bouton "Plus tard" (dismiss)
 */
@Composable
private fun BumpEffortDialog(
    prompt: UserPrompt,
    onDismiss: () -> Unit,
    onBump: () -> Unit,
    onEditRating: () -> Unit,
) {
    val ctx = prompt.context
    val taskTitle = ctx["task_title"]?.toString() ?: "?"
    val windowDays = (ctx["window_days"] as? Number)?.toInt() ?: 0
    val bonusRatio = (ctx["bonus_ratio"] as? Number)?.toFloat() ?: 0f
    @Suppress("UNCHECKED_CAST")
    val completions = (ctx["completions"] as? List<Map<String, Any?>>) ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Tache souvent en retard",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC2410C),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "${(bonusRatio * 100).toInt()}% des completions sont en retard " +
                        "sur les $windowDays derniers jours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Dernieres completions",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))

                completions.take(6).forEach { comp ->
                    val reason = comp["reason"]?.toString() ?: "NEUTRAL"
                    val isBonus = reason == "BONUS_RETARD"
                    val at = comp["completed_at"]?.toString()?.take(10) ?: "?"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isBonus) "🔥" else "✅",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = at,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (isBonus) {
                            Text(
                                text = "en retard",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFC2410C),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cette tache rapporte peut-etre pas assez de points. " +
                        "Augmenter l'effort d'un cran ?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onBump,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⬆ Bumper l'effort (+1)") }

                OutlinedButton(
                    onClick = onEditRating,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Modifier les notes") }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Plus tard") }
            }
        },
        dismissButton = {},
    )
}

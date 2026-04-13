package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.util.FreshnessCalculator
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Resultat de la popup rating : affinity, effort, et update de recurrence
 * optionnel. Partage entre HomeScreen et TaskDetailScreen.
 */
data class RatingRecurrence(
    val makeRecurring: Boolean = false,
    val everyNDays: Int = 7,
)

/**
 * Modal de notation unifie — utilise depuis 3 points d'entree :
 * 1. HomeScreen popup auto (apres completion d'une tache pending)
 * 2. HomeScreen bouton "?" (notation manuelle, oldest-rating)
 * 3. TaskDetailScreen bouton "Modifier" sur la card "Effort & Affinite"
 *
 * Le contrat d'entree est `PendingAffinity` (un seul DTO partage entre les
 * 3 endpoints backend : /pending-affinity, /oldest-rating, /tasks/{id}/rating-context).
 *
 * @param onSubmitAndNext callback chaine vers la tache pending suivante
 *   (Home uniquement). Si null, le bouton "Une autre !" n'est pas affiche.
 * @param roomLabel libelle de la piece a afficher sous le titre (ex: "🛁 SDB").
 *   Null = aucun rappel de piece. Caller resoud depuis le cache rooms.
 */
@Composable
fun RatingDialog(
    pending: PendingAffinity,
    onSubmit: (Int, Int, RatingRecurrence?) -> Unit,
    onSubmitAndNext: ((Int, Int, RatingRecurrence?) -> Unit)? = null,
    onDismiss: () -> Unit,
    roomLabel: String? = null,
) {
    // Pre-remplissage avec les valeurs serveur. Key sur taskId pour
    // reinitialiser quand on change de tache (flow Home "Une autre !").
    var affinityValue by remember(pending.taskId) {
        mutableFloatStateOf((pending.currentAffinity ?: 5).toFloat())
    }
    // Effort cap a 12 (5min-1h) — aligne avec la validation backend.
    var effortValue by remember(pending.taskId) {
        mutableFloatStateOf(((pending.currentEffortPoints ?: 3).coerceIn(1, 12)).toFloat())
    }

    // Recurrence : analyser la rule existante.
    val currentRecurrence = pending.recurrenceRule
    val isAlreadyInterval = currentRecurrence?.type == "interval"
    val currentDays = currentRecurrence?.everyNDays ?: 7

    var makeRecurring by remember(pending.taskId) { mutableStateOf(isAlreadyInterval) }
    val minDays = if (isAlreadyInterval) (currentDays * 0.5f).coerceAtLeast(1f) else 1f
    val maxDays = if (isAlreadyInterval) (currentDays * 2f).coerceAtLeast(2f) else 30f
    var daysValue by remember(pending.taskId) { mutableFloatStateOf(currentDays.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                if (pending.pendingTotal != null && pending.pendingTotal > 0) {
                    Text(
                        text = "${pending.pendingTotal} tache(s) a noter",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = pending.title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (roomLabel != null) {
                    Text(
                        text = roomLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatLastRated(pending.lastRatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (pending.lastRatedAt == null) FontStyle.Italic else FontStyle.Normal,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // === Affinite ===
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Affinite", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Affinite",
                        description = "Est-ce que tu aimes faire cette tache ? 1 = pas du tout, 10 = j'adore.",
                    )
                }
                Text(
                    text = "${affinityValue.toInt()}/10",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Slider(
                    value = affinityValue,
                    onValueChange = { affinityValue = it },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pas du tout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("A fond !", style = MaterialTheme.typography.labelSmall, color = FreshnessCalculator.VERT)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Effort ===
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Effort", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = "Effort",
                        description = "Estimation du temps que prend cette tache. Sert a calculer les points.",
                    )
                }
                Text(
                    text = effortPointsToTime(effortValue.toInt()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Slider(
                    value = effortValue,
                    onValueChange = { effortValue = it },
                    valueRange = 1f..12f, // 5min a 1h
                    steps = 10,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("5min", style = MaterialTheme.typography.labelSmall)
                    Text("1h", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Frequence ===
                if (isAlreadyInterval) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Frequence : tous les ${daysValue.toInt()} jours",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        InfoTooltip(
                            title = "Frequence",
                            description = "Ajuste la frequence de la tache. Tu peux modifier de 50% a 200% de la valeur actuelle ($currentDays jours).",
                        )
                    }
                    Slider(
                        value = daysValue,
                        onValueChange = { daysValue = it },
                        valueRange = minDays..maxDays,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${minDays.toInt()}j", style = MaterialTheme.typography.labelSmall)
                        Text("${maxDays.toInt()}j", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recurrente ?", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Faire revenir cette tache regulierement",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = makeRecurring,
                            onCheckedChange = { makeRecurring = it },
                        )
                    }
                    if (makeRecurring) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tous les ${daysValue.toInt()} jours",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Slider(
                            value = daysValue,
                            onValueChange = { daysValue = it },
                            valueRange = 1f..30f,
                            steps = 28,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSubmitAndNext != null) {
                    OutlinedButton(onClick = {
                        val rec = if (isAlreadyInterval || makeRecurring) {
                            RatingRecurrence(true, daysValue.toInt())
                        } else null
                        onSubmitAndNext(affinityValue.toInt(), effortValue.toInt(), rec)
                    }) { Text("Une autre !") }
                }
                Button(onClick = {
                    val rec = if (isAlreadyInterval || makeRecurring) {
                        RatingRecurrence(true, daysValue.toInt())
                    } else null
                    onSubmit(affinityValue.toInt(), effortValue.toInt(), rec)
                }) { Text("Ok") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Plus tard") }
        },
    )
}

private fun effortPointsToTime(points: Int): String {
    val minutes = points * 5
    return when {
        minutes >= 60 -> "${minutes / 60}h${if (minutes % 60 > 0) "%02d".format(minutes % 60) else ""}"
        else -> "${minutes}min"
    }
}

/**
 * Format relatif de la derniere notation (sans absolu en fallback, choix
 * UX deliberé). "Jamais note" si null. "A l'instant" si < 1min.
 */
private fun formatLastRated(iso: String?): String {
    if (iso == null) return "Jamais note"
    return try {
        val instant = try {
            Instant.parse(iso)
        } catch (_: Exception) {
            Instant.parse("${iso}Z")
        }
        val now = Instant.now()
        val minutes = ChronoUnit.MINUTES.between(instant, now)
        val hours = ChronoUnit.HOURS.between(instant, now)
        val days = ChronoUnit.DAYS.between(instant, now)
        when {
            minutes < 1 -> "Derniere notation a l'instant"
            minutes < 60 -> "Derniere notation il y a ${minutes}min"
            hours < 24 -> "Derniere notation il y a ${hours}h"
            days < 30 -> "Derniere notation il y a ${days}j"
            days < 365 -> "Derniere notation il y a ${days / 30} mois"
            else -> "Derniere notation il y a ${days / 365} an${if (days / 365 > 1) "s" else ""}"
        }
    } catch (_: Exception) {
        "Jamais note"
    }
}

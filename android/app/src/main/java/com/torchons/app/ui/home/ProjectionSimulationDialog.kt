package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.util.FreshnessCalculator
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Popup de simulation "Juste pour moi" (v0.8.0 Feature 3 magic buttons).
 *
 * Version v0.8.0 limitee : un seul slider (celui de l'user courant), pas de
 * multi-slider ni de flow "proposer au foyer". Ces features arrivent en v0.8.1.
 *
 * Contenu :
 * - Toggle unite min/jour vs taches/jour (sauve dans user_foyers.objective_display_unit)
 * - Slider perso, borne 0 a max(60, 2 x moyenne 14j), step 5 min
 * - Live feedback : "A ce rythme, rattrapage en X jours" ou "rythme insuffisant,
 *   minimum X min/j pour rattraper"
 * - Maintien post-rattrapage
 * - Boutons "Annuler" / "Definir comme objectif"
 */
@Composable
fun ProjectionSimulationDialog(
    pace: PaceResponse?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    currentUserId: String?,
    onDismiss: () -> Unit,
    onSave: (minutes: Int?, unit: String) -> Unit,
) {
    val me = pace?.users?.find { it.userId == currentUserId }
    val initialUnit = me?.objectiveDisplayUnit ?: "min"
    val currentAvg = (me?.minutesPerDayAvg ?: 0.0).coerceAtLeast(0.0)
    val initialMinutes = me?.dailyObjectiveMinutes?.toFloat() ?: currentAvg.toFloat()

    // Bornes auto-adaptees : max = max(60, 2 * moyenne 14j), arrondi au 5 min
    val maxMinutes = remember(currentAvg) {
        val computed = max(60.0, currentAvg * 2.0)
        (ceil(computed / 5.0) * 5.0).toFloat().coerceAtMost(240f)
    }

    var unit by remember { mutableStateOf(initialUnit) }
    var sliderMinutes by remember { mutableFloatStateOf(initialMinutes.coerceIn(0f, maxMinutes)) }

    val retardMin = retard?.totalMinutes ?: 0
    val maintPerDay = maintenance?.minutesPerDay ?: 0.0

    // Rate de foyer a cette simulation : autres users a leur moyenne 14j
    // actuelle, moi au slider. Pas de multi-slider v0.8.0.
    val otherRateTotal = pace?.users
        ?.filter { it.userId != currentUserId }
        ?.sumOf { it.minutesPerDayAvg }
        ?: 0.0
    val foyerRate = otherRateTotal + sliderMinutes.toDouble()
    val netRate = foyerRate - maintPerDay
    val daysToCatchUp = when {
        retardMin <= 0 -> 0
        netRate <= 0 -> null
        else -> ceil(retardMin / netRate).toInt()
    }

    val sliderTasks = (sliderMinutes / 5f).roundToInt().coerceAtLeast(0)
    val maxTasks = (maxMinutes / 5f).roundToInt().coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (retardMin > 0) "Simulation rattrapage" else "Ton rythme cible",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Toggle unite
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = unit == "min",
                        onClick = { unit = "min" },
                        label = { Text("Minutes") },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                    FilterChip(
                        selected = unit == "tasks",
                        onClick = { unit = "tasks" },
                        label = { Text("Taches") },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Etat actuel
                if (retardMin > 0) {
                    Text(
                        text = "Retard actuel : ${formatMinutesLong(retardMin)} (${retard?.taskCount ?: 0} taches)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshnessCalculator.ROUGE,
                    )
                } else {
                    Text(
                        text = "Zero retard ! Fixe ton rythme cible de maintien.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshnessCalculator.VERT,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Slider perso
                Text(
                    text = if (unit == "tasks") {
                        "Ton engagement : $sliderTasks taches/jour"
                    } else {
                        "Ton engagement : ${sliderMinutes.roundToInt()} min/jour"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = sliderMinutes,
                    onValueChange = { sliderMinutes = it },
                    valueRange = 0f..maxMinutes,
                    steps = if (unit == "tasks") {
                        (maxTasks - 1).coerceAtLeast(0)
                    } else {
                        ((maxMinutes / 5f).toInt() - 1).coerceAtLeast(0)
                    },
                )
                Text(
                    text = "Ton rythme moyen sur 14 jours : ${currentAvg.roundToInt()} min/j",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Feedback live
                if (retardMin > 0) {
                    when {
                        daysToCatchUp == null -> Text(
                            text = "Rythme insuffisant pour rattraper. Minimum foyer : ${(maintPerDay + 1).toInt()} min/j.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FreshnessCalculator.ROUGE,
                            fontWeight = FontWeight.SemiBold,
                        )
                        daysToCatchUp == 0 -> Text(
                            text = "Deja rattrape !",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FreshnessCalculator.VERT,
                        )
                        else -> Text(
                            text = "A ce rythme : rattrapage en $daysToCatchUp jour${if (daysToCatchUp > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (maintPerDay > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (retardMin > 0) {
                            "Puis pour maintenir : ${maintPerDay.toInt()} min/j combines pour le foyer"
                        } else {
                            "Pour maintenir zero retard : ${maintPerDay.toInt()} min/j combines pour le foyer"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val minutes = sliderMinutes.roundToInt()
                onSave(minutes, unit)
            }) { Text("Definir comme objectif") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private fun formatMinutesLong(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h$m"
}

package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
 * Popup multi-slider de simulation (v0.8.1 Feature 3 magic buttons).
 *
 * Contient un slider par user du foyer. Le user courant (me) a toujours son
 * slider, les autres sont editables pour simuler "si on proposait ce plan-la".
 *
 * Boutons :
 * - "Juste pour moi" : sauve uniquement le slider perso, ignore les autres.
 *   Toujours enabled.
 * - "Proposer au foyer" : declenche le flow live proposition. Enabled
 *   uniquement si au moins un slider d'un autre user a ete modifie (sinon
 *   proposer ne sert a rien, juste pour moi suffit).
 */
@Composable
fun ProjectionSimulationDialog(
    pace: PaceResponse?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    currentUserId: String?,
    onDismiss: () -> Unit,
    onSaveSelf: (minutes: Int, unit: String) -> Unit,
    onProposeToFoyer: (sliderValues: Map<String, Int>, unit: String) -> Unit,
) {
    val users = pace?.users ?: emptyList()
    if (users.isEmpty() || currentUserId == null) {
        // Pas de donnees encore : dismiss
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Chargement...") },
            text = { Text("Les donnees ne sont pas encore pretes, ferme et reessaie.") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            },
        )
        return
    }

    val me = users.find { it.userId == currentUserId }
    val initialUnit = me?.objectiveDisplayUnit ?: "min"
    var unit by remember { mutableStateOf(initialUnit) }

    // Un slider par user du foyer. Initialise a (objectif actuel ?: moyenne 14j).
    // Stocke les valeurs en min/j (unite de reference).
    val sliderValues = remember {
        mutableStateMapOf<String, Float>().apply {
            users.forEach { u ->
                val initial = u.dailyObjectiveMinutes?.toFloat()
                    ?: u.minutesPerDayAvg.toFloat().coerceAtLeast(0f)
                put(u.userId, initial)
            }
        }
    }

    // Track quels sliders des autres users ont ete modifies (pour activer
    // le bouton "Proposer au foyer")
    val otherModified = remember { mutableStateMapOf<String, Boolean>() }

    // Bornes max par user : max(60, 2 x moyenne14j) arrondi au 5 min,
    // cap a 240 (4h). Cache par user.
    val maxByUser = remember {
        users.associate { u ->
            val computed = max(60.0, u.minutesPerDayAvg * 2.0)
            u.userId to (ceil(computed / 5.0) * 5.0).toFloat().coerceAtMost(240f)
        }
    }

    val retardMin = retard?.totalMinutes ?: 0
    val maintPerDay = maintenance?.minutesPerDay ?: 0.0

    // Calcul live : rythme total foyer = somme des sliders. Rate net = total - maintenance.
    val foyerRate = sliderValues.values.sumOf { it.toDouble() }
    val netRate = foyerRate - maintPerDay
    val daysToCatchUp = when {
        retardMin <= 0 -> 0
        netRate <= 0 -> null
        else -> ceil(retardMin / netRate).toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (retardMin > 0) "Simulation rattrapage" else "Rythme cible du foyer",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

                // Etat actuel
                if (retardMin > 0) {
                    Text(
                        text = "Retard : ${formatMinutesLong(retardMin)} (${retard?.taskCount ?: 0} taches)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshnessCalculator.ROUGE,
                    )
                } else {
                    Text(
                        text = "Zero retard ! Fixe le rythme cible de maintien.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshnessCalculator.VERT,
                    )
                }

                // Un slider par user
                users.forEach { u ->
                    val value = sliderValues[u.userId] ?: 0f
                    val maxVal = maxByUser[u.userId] ?: 60f
                    val isMe = u.userId == currentUserId
                    val label = u.displayName + if (isMe) " (toi)" else ""
                    val displayed = if (unit == "tasks") {
                        "${(value / 5f).roundToInt()} taches/j"
                    } else {
                        "${value.roundToInt()} min/j"
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = displayed,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = value,
                        onValueChange = { newVal ->
                            sliderValues[u.userId] = newVal
                            if (!isMe) otherModified[u.userId] = true
                        },
                        valueRange = 0f..maxVal,
                        steps = ((maxVal / 5f).toInt() - 1).coerceAtLeast(0),
                    )
                    Text(
                        text = "Moyenne 14j : ${u.minutesPerDayAvg.roundToInt()} min/j",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Feedback live
                if (retardMin > 0) {
                    when {
                        daysToCatchUp == null -> Text(
                            text = "Rythme insuffisant pour rattraper. Il faut > ${maintPerDay.toInt()} min/j combines.",
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
                            text = "A ces rythmes : rattrapage en $daysToCatchUp jour${if (daysToCatchUp > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (maintPerDay > 0) {
                    Text(
                        text = if (retardMin > 0) {
                            "Puis pour maintenir : ${maintPerDay.toInt()} min/j combines"
                        } else {
                            "Maintien : ${maintPerDay.toInt()} min/j combines"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            val otherTouched = otherModified.values.any { it }
            Button(
                onClick = {
                    // Proposer au foyer uniquement si un autre slider a bouge
                    val map = sliderValues.mapValues { it.value.roundToInt() }
                    onProposeToFoyer(map, unit)
                },
                enabled = otherTouched,
            ) { Text("Proposer au foyer") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Annuler") }
                OutlinedButton(onClick = {
                    val mineValue = sliderValues[currentUserId]?.roundToInt() ?: 0
                    onSaveSelf(mineValue, unit)
                }) { Text("Juste pour moi") }
            }
        },
    )
}

private fun formatMinutesLong(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h$m"
}

package com.torchons.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torchons.app.data.model.UserPrompt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dialog de resolution des jours incertains (home freeze v2).
 *
 * Deux modes :
 * - calendar (<=30 jours) : grille de jours avec toggle present/absent
 * - simplified (>30 jours) : ecran avec date + choix "tout absent depuis"
 *   ou saisie du nombre de jours d'absence
 */
@Composable
fun ResolvePresenceDialog(
    prompt: UserPrompt,
    onResolve: (payload: Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val mode = prompt.context["mode"]?.toString() ?: "calendar"
    val lastConfirmedDate = prompt.context["last_confirmed_date"]?.toString() ?: ""

    if (mode == "calendar") {
        CalendarModeDialog(
            prompt = prompt,
            lastConfirmedDate = lastConfirmedDate,
            onResolve = onResolve,
            onDismiss = onDismiss,
            onNavigateToSettings = onNavigateToSettings,
        )
    } else {
        SimplifiedModeDialog(
            prompt = prompt,
            lastConfirmedDate = lastConfirmedDate,
            onResolve = onResolve,
            onDismiss = onDismiss,
            onNavigateToSettings = onNavigateToSettings,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarModeDialog(
    prompt: UserPrompt,
    lastConfirmedDate: String,
    onResolve: (payload: Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    val uncertainDays = (prompt.context["uncertain_days"] as? List<*>)
        ?.mapNotNull { it?.toString() } ?: emptyList()

    // Etat : chaque jour est pre-coche "present" par defaut
    val dayStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            uncertainDays.forEach { put(it, true) } // true = present
        }
    }
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo) {
        InfoDialog(onDismiss = { showInfo = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jours a confirmer", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "Info")
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Ces jours n'ont pas ete detectes automatiquement. " +
                    "Les jours marques \"present\" sont en vert, ceux " +
                    "\"absent\" en gris. Appuie sur un jour pour changer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Grille de jours avec en-tetes L M M J V S D
                val dayHeaders = listOf("L", "M", "M", "J", "V", "S", "D")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    dayHeaders.forEach { h ->
                        Text(
                            h,
                            modifier = Modifier.size(36.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // Generer les semaines
                if (uncertainDays.isNotEmpty()) {
                    val dates = uncertainDays.map { LocalDate.parse(it) }.sorted()
                    val firstDate = dates.first()
                    // Aligner sur le lundi de la premiere semaine
                    val startOfWeek = firstDate.with(DayOfWeek.MONDAY)
                    val lastDate = dates.last()
                    val endOfWeek = lastDate.with(DayOfWeek.SUNDAY)

                    var cursor = startOfWeek
                    while (cursor <= endOfWeek) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            for (dow in 1..7) {
                                val day = cursor
                                val dayStr = day.toString()
                                val isUncertain = dayStr in dayStates

                                if (isUncertain) {
                                    val isPresent = dayStates[dayStr] == true
                                    val bgColor by animateColorAsState(
                                        if (isPresent) Color(0xFF10B981) else Color(0xFFD1D5DB),
                                        label = "dayColor",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bgColor)
                                            .clickable { dayStates[dayStr] = !isPresent },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "${day.dayOfMonth}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isPresent) Color.White else Color(0xFF6B7280),
                                        )
                                    }
                                } else {
                                    // Jour hors de la plage incertaine (padding)
                                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                        if (day >= firstDate && day <= lastDate) {
                                            // Jour dans la plage mais deja confirme
                                            Text(
                                                "${day.dayOfMonth}",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                            )
                                        }
                                    }
                                }
                                cursor = cursor.plusDays(1)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onNavigateToSettings) {
                    Text("Desactiver cette fonctionnalite", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val days = dayStates.mapValues { (_, present) -> if (present) "present" else "absent" }
                onResolve(mapOf("days" to days))
            }) {
                Text("Valider")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Plus tard")
            }
        },
    )
}

@Composable
private fun SimplifiedModeDialog(
    prompt: UserPrompt,
    lastConfirmedDate: String,
    onResolve: (payload: Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val count = (prompt.context["uncertain_days_count"] as? Number)?.toInt() ?: 0
    val formattedDate = try {
        val date = LocalDate.parse(lastConfirmedDate)
        date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
    } catch (_: Exception) {
        lastConfirmedDate
    }

    var showCustomInput by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo) {
        InfoDialog(onDismiss = { showInfo = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Longue absence detectee", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "Info")
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "La derniere activite detectee a la maison date du $formattedDate ($count jours sans signal).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (!showCustomInput) {
                    Button(
                        onClick = { onResolve(mapOf("absent_since" to lastConfirmedDate)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("J'etais absent depuis cette date")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showCustomInput = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Specifier le nombre de jours d'absence")
                    }
                } else {
                    Text(
                        "Combien de jours etais-tu absent ?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customDays,
                            onValueChange = { customDays = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.width(100.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            label = { Text("Jours") },
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                val n = customDays.toIntOrNull() ?: 0
                                if (n > 0) {
                                    val absentSince = LocalDate.now().minusDays(n.toLong()).toString()
                                    onResolve(mapOf("absent_since" to absentSince))
                                }
                            },
                            enabled = (customDays.toIntOrNull() ?: 0) > 0,
                        ) {
                            Text("Valider")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onNavigateToSettings) {
                    Text("Desactiver cette fonctionnalite", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Plus tard")
            }
        },
    )
}

/** Dialog d'information sur le home freeze. */
@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pourquoi cette question ?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Quand personne n'est a la maison, les taches menageres sont mises " +
                    "en pause — elles n'accumulent pas de retard pendant ton absence.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Exemple : \"Passer l'aspirateur\" est a faire tous les 7 jours. " +
                    "Tu pars en vacances 5 jours. Sans le gel, tu reviens et la tache " +
                    "apparait en retard. Avec le gel, les 5 jours d'absence ne comptent " +
                    "pas — il te reste encore 2 jours pour la faire.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Pour que ca fonctionne, on a besoin de savoir quels jours quelqu'un " +
                    "etait a la maison.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Compris")
            }
        },
    )
}

package com.torchons.app.ui.common

import android.content.res.Resources
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.DurationUnit
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

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
    // Cap le haut du slider a 365j pour rester dans la fenetre acceptee par
    // le backend (FrequencyUpdate.every_n_days: Field(ge=1, le=365)). Sans
    // clamp, une tache a 200j donnerait un max = 400j, rejete en 422 cote
    // backend, et le worker rejouerait l'op a l'infini (cf. v0.15.3).
    val maxDays = if (isAlreadyInterval) {
        (currentDays * 2f).coerceIn(2f, 365f)
    } else {
        30f
    }
    var daysValue by remember(pending.taskId) { mutableFloatStateOf(currentDays.toFloat()) }
    // Unite locale pour le slider de frequence — defaut sur l'unite la plus
    // naturelle pour la valeur courante (1 mois ↔ 30 j, 1 sem ↔ 7 j).
    var freqUnit by remember(pending.taskId) {
        mutableStateOf(DurationUnit.bestFor(currentDays))
    }

    val res = LocalContext.current.resources

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                if (pending.pendingTotal != null && pending.pendingTotal > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.rating_pending_count,
                            pending.pendingTotal,
                            pending.pendingTotal,
                        ),
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
                    text = formatLastRated(res, pending.lastRatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (pending.lastRatedAt == null) FontStyle.Italic else FontStyle.Normal,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // === Affinity ===
                val affinityLabel = stringResource(R.string.rating_affinity_label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(affinityLabel, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = affinityLabel,
                        description = stringResource(R.string.rating_affinity_tooltip_description),
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
                    Text(stringResource(R.string.rating_affinity_min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.rating_affinity_max), style = MaterialTheme.typography.labelSmall, color = TaskUrgencyCalculator.VERT)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Effort ===
                val effortLabel = stringResource(R.string.rating_effort_label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(effortLabel, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    InfoTooltip(
                        title = effortLabel,
                        description = stringResource(R.string.rating_effort_tooltip_description),
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
                    Text(stringResource(R.string.rating_effort_min), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.rating_effort_max), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === Frequency ===
                // Helper local pour snapper la valeur sur les multiples de
                // l'unite courante (1 j / 7 j / 30 j) — partage entre les
                // deux branches isAlreadyInterval / makeRecurring.
                fun snapToUnit(raw: Float, lo: Float, hi: Float): Float {
                    val r = raw.roundToInt()
                    val s = if (freqUnit == DurationUnit.JOURS) r
                    else freqUnit.toDays(freqUnit.fromDays(r))
                    return s.coerceIn(lo.toInt(), hi.toInt()).toFloat()
                }
                if (isAlreadyInterval) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                R.string.rating_frequency_value_format,
                                DurationFormat.formatDaysHuman(res, daysValue.toInt(), long = true),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        InfoTooltip(
                            title = stringResource(R.string.rating_frequency_label),
                            description = stringResource(
                                R.string.rating_frequency_tooltip_description_format,
                                DurationFormat.formatDaysHuman(res, currentDays, long = true),
                            ),
                        )
                    }
                    UnitToggleRow(
                        unit = freqUnit,
                        onUnitChange = { u ->
                            freqUnit = u
                            daysValue = u.toDays(u.fromDays(daysValue.toInt()))
                                .coerceIn(minDays.toInt(), maxDays.toInt()).toFloat()
                        },
                        sliderMaxDays = maxDays.toInt(),
                    )
                    Slider(
                        value = daysValue,
                        onValueChange = { daysValue = snapToUnit(it, minDays, maxDays) },
                        valueRange = minDays..maxDays,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(DurationFormat.formatDaysHuman(res, minDays.toInt()), style = MaterialTheme.typography.labelSmall)
                        Text(DurationFormat.formatDaysHuman(res, maxDays.toInt()), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.rating_recurring_switch_label), style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(R.string.rating_recurring_switch_description),
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
                            stringResource(
                                R.string.rating_recurring_every_format,
                                DurationFormat.formatDaysHuman(res, daysValue.toInt(), long = true),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        UnitToggleRow(
                            unit = freqUnit,
                            onUnitChange = { u ->
                                freqUnit = u
                                daysValue = u.toDays(u.fromDays(daysValue.toInt()))
                                    .coerceIn(1, 30).toFloat()
                            },
                            sliderMaxDays = 30,
                        )
                        Slider(
                            value = daysValue,
                            onValueChange = { daysValue = snapToUnit(it, 1f, 30f) },
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
                    }) { Text(stringResource(R.string.rating_button_next)) }
                }
                Button(onClick = {
                    val rec = if (isAlreadyInterval || makeRecurring) {
                        RatingRecurrence(true, daysValue.toInt())
                    } else null
                    onSubmit(affinityValue.toInt(), effortValue.toInt(), rec)
                }) { Text(stringResource(R.string.common_ok)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rating_button_later)) }
        },
    )
}

/**
 * Petite ligne de boutons segmentes Jours / Semaines / Mois — partagee
 * entre RatingDialog et FrequencyVoteDialog. Un cran est desactive si
 * le slider courant ne peut pas l'accommoder (sliderMaxDays < unit.days),
 * ex: pas de "Mois" sur un slider qui plafonne a 14 j.
 */
@Composable
private fun UnitToggleRow(
    unit: DurationUnit,
    onUnitChange: (DurationUnit) -> Unit,
    sliderMaxDays: Int,
) {
    val units = DurationUnit.values().toList()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        units.forEachIndexed { idx, u ->
            SegmentedButton(
                selected = unit == u,
                onClick = { onUnitChange(u) },
                shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                enabled = sliderMaxDays >= u.days,
            ) { Text(stringResource(u.labelRes)) }
        }
    }
}

/**
 * Format relatif de la derniere notation (sans absolu en fallback, choix
 * UX deliberé). "Jamais note" si null. "A l'instant" si < 1min.
 */
private fun formatLastRated(res: Resources, iso: String?): String {
    if (iso == null) return res.getString(R.string.rating_last_rated_never)
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
            minutes < 1 -> res.getString(R.string.rating_last_rated_just_now)
            minutes < 60 -> res.getString(R.string.rating_last_rated_minutes_format, minutes.toInt())
            hours < 24 -> res.getString(R.string.rating_last_rated_hours_format, hours.toInt())
            days < 30 -> res.getString(R.string.rating_last_rated_days_format, days.toInt())
            days < 365 -> {
                val months = (days / 30).toInt()
                res.getQuantityString(R.plurals.rating_last_rated_months, months, months)
            }
            else -> {
                val years = (days / 365).toInt()
                res.getQuantityString(R.plurals.rating_last_rated_years, years, years)
            }
        }
    } catch (_: Exception) {
        res.getString(R.string.rating_last_rated_never)
    }
}

package com.torchons.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.Suggestion
import com.torchons.app.ui.common.SoloPointsInfoDialog
import com.torchons.app.util.CompletionDateLabels

// Pieces of the home suggestion card, moved out of HomeScreen on 2026-11-18 (file at its size
// cap), behaviour unchanged.

/** Arrow navigation depth: past this many days back, the left slot turns
 *  into a calendar shortcut (the picker becomes the only way further). */
internal const val MAX_ARROW_NAV_DAYS = 15

/**
 * Participant selector anchored to the chevron slot of the "Together"
 * button. Two-member household: radio choice between "Together" (both)
 * and "Just <other>". Three or more: one checkbox row per member (at
 * least one must stay selected), menu stays open while toggling.
 */
@Composable
internal fun ParticipantsMenu(
    expanded: Boolean,
    members: List<Pair<String, String>>,
    currentUserId: String,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val memberIds = members.map { it.first }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (members.size == 2) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_participants_together)) },
                leadingIcon = {
                    RadioButton(selected = selectedIds.size == memberIds.size, onClick = null)
                },
                onClick = {
                    onSelectionChange(memberIds.toSet())
                    onDismiss()
                },
            )
            val other = members.firstOrNull { it.first != currentUserId }
            if (other != null) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.home_participants_just_other_format, other.second))
                    },
                    leadingIcon = {
                        RadioButton(selected = selectedIds == setOf(other.first), onClick = null)
                    },
                    onClick = {
                        onSelectionChange(setOf(other.first))
                        onDismiss()
                    },
                )
            }
        } else {
            members.forEach { (id, name) ->
                val checked = id in selectedIds
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = {
                        val next = when {
                            !checked -> selectedIds + id
                            selectedIds.size > 1 -> selectedIds - id
                            else -> selectedIds // never allow an empty selection
                        }
                        onSelectionChange(next)
                    },
                )
            }
        }
    }
}

/**
 * Material3 DatePickerDialog restricted to past dates (today included).
 * Only sets the shared selected date — completion stays on the center tap.
 * Millis are interpreted as UTC calendar days, consistent with
 * [CompletionDateLabels.millisToIsoNoonUtc].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PastOnlyDatePickerDialog(
    initialDate: java.time.LocalDate,
    onDateSelected: (java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Bound by the user's local "today": no future day can be picked.
    val maxDate = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = java.time.Instant.ofEpochMilli(utcTimeMillis)
                    .atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                return !date.isAfter(maxDate)
            }

            override fun isSelectableYear(year: Int): Boolean = year <= maxDate.year
        },
    )

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    onDateSelected(
                        java.time.Instant.ofEpochMilli(millis)
                            .atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                    )
                }
            }) {
                Text(stringResource(R.string.common_validate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

/**
 * Ligne "💰 N pts" sous la card home, juste au-dessus du bouton "Fait !".
 *
 * Affiche les points predits par /api/suggestions (predicted_final_points)
 * pour rendre la gamification lisible AVANT la completion (l'utilisateur
 * sait combien il va gagner avant de cliquer).
 *
 * Trois cas visuels :
 *  - Solo (predicted = 0) : "💰 0 pt" en gris + icone (i) qui ouvre
 *    SoloPointsInfoDialog (pedago "pourquoi 0 ?").
 *  - Bonus retard eligible (bonusEligible) : "💰 N pts" en vert avec un
 *    suffixe "+M pts bonus retard" pour rendre le bonus tangible.
 *  - Cas standard : "💰 N pts" neutre.
 */
@Composable
internal fun SuggestionPointsLine(
    suggestion: Suggestion,
    /** Date-aware server preview; overrides the /suggestions prediction
     *  when the user navigated to a past day (late bonus may disappear,
     *  an early malus may appear). Null = J-0 or offline fallback. */
    preview: com.torchons.app.data.model.CompletionPreview? = null,
) {
    var showInfo by remember { mutableStateOf(false) }
    val finalPts = preview?.finalPoints ?: suggestion.predictedFinalPoints
    val basePts = preview?.basePoints ?: suggestion.predictedBasePoints
    val delta = finalPts - basePts
    // Solo detection stays on the suggestion values: a solo task predicts
    // 0 whatever the date, while a preview could legitimately reach 0.
    val isSolo = suggestion.predictedFinalPoints == 0 && suggestion.predictedBasePoints == 0
    val bonusShown = delta > 0 && (preview?.reason == "BONUS_RETARD" || (preview == null && suggestion.bonusEligible))
    val malusShown = delta < 0
    val color = when {
        isSolo -> MaterialTheme.colorScheme.onSurfaceVariant
        bonusShown -> Color(0xFF2E7D32)
        malusShown -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "💰 ",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = pluralStringResource(R.plurals.suggestion_predicted_points, finalPts, finalPts),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (!isSolo && bonusShown) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.suggestion_bonus_delta_points, delta, delta),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (!isSolo && malusShown) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.suggestion_malus_delta_points, -delta, -delta),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (isSolo) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.suggestion_solo_points_info_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { showInfo = true },
            )
        }
    }

    if (showInfo) {
        SoloPointsInfoDialog(onDismiss = { showInfo = false })
    }
}

/**
 * Ligne de meta sous un titre de tache : emoji+nom de la piece + nom de
 * la categorie en pastille discrete. Affiche rien si les deux sont null.
 *
 * Pattern utilise par SuggestionCard pour rappeler le contexte (ou est
 * la tache, dans quel "univers" elle vit).
 */
@Composable
internal fun TaskMetaRow(room: TaskContextRef?, category: TaskContextRef?) {
    if (room == null && category == null) return
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (room != null) {
            val label = if (!room.emoji.isNullOrBlank()) "${room.emoji} ${room.name}" else room.name
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (category != null) {
            val catColor = remember(category.color) {
                try { Color(android.graphics.Color.parseColor(category.color)) }
                catch (_: Exception) { Color.Gray }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(catColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = catColor,
                )
            }
        }
    }
}

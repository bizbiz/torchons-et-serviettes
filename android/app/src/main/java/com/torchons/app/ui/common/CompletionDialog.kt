package com.torchons.app.ui.common

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mode d'affichage du dialog de completion :
 * - Single : "deja fait" solo, radio button, 1 seul participant
 * - Multi : "fait ensemble", checkboxes, min 1 participant
 */
enum class CompletionDialogMode { Single, Multi }

/**
 * Dialog unifie pour "deja fait" (mode Single) et "fait ensemble" (mode Multi).
 *
 * - Mode Single : radio des membres, pre-selection sur currentUserId si possible,
 *   sinon sur le premier assigne. Bouton "A plusieurs..." pour basculer en Multi
 *   (via le callback onSwitchToMulti).
 * - Mode Multi : checkboxes, assignes pre-coches, bouton "Ajouter un membre"
 *   pour reveler les membres non assignes. Min 1 coche pour valider.
 *
 * La selection de date se fait via un bouton qui ouvre un DatePickerDialog
 * natif Material3 en sub-modal. La date est stockee en millis UTC et
 * retournee en ISO 8601 (T12:00:00Z) pour eviter les decalages TZ.
 *
 * N.B. : pour le cas foyer 2-pers + Multi, l'ecran parent n'instancie PAS ce
 * composable et ouvre directement un DatePickerDialog (pas de picker de
 * participants, split auto).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletionDialog(
    taskTitle: String,
    mode: CompletionDialogMode,
    allMembers: List<Pair<String, String>>,
    assignedMemberIds: Set<String>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onConfirm: (participantIds: List<String>, isoDate: String) -> Unit,
    onSwitchToMulti: (() -> Unit)? = null,
) {
    // Date selectionnee (defaut = maintenant).
    var selectedDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePickerSub by remember { mutableStateOf(false) }

    // Mode Multi : extension pour reveler les membres non assignes.
    var showAllMembers by remember { mutableStateOf(false) }

    // Mode Single : id radio selectionne. Defaut = user courant si assigne,
    // sinon premier assigne, sinon premier membre.
    var singleSelectedId by remember {
        mutableStateOf(
            when {
                assignedMemberIds.contains(currentUserId) -> currentUserId
                assignedMemberIds.isNotEmpty() ->
                    allMembers.firstOrNull { assignedMemberIds.contains(it.first) }?.first
                        ?: currentUserId
                else -> allMembers.firstOrNull()?.first.orEmpty()
            }
        )
    }

    // Mode Multi : set des ids coches. Defaut = assignes.
    var multiSelectedIds by remember {
        mutableStateOf(assignedMemberIds.toSet())
    }

    // Liste des membres visibles dans le picker selon mode + extension.
    val visibleMembers = when (mode) {
        CompletionDialogMode.Single -> allMembers
        CompletionDialogMode.Multi ->
            if (showAllMembers) allMembers
            else allMembers.filter { (id, _) -> assignedMemberIds.contains(id) }
    }

    val hasHiddenMembers = mode == CompletionDialogMode.Multi
        && !showAllMembers
        && allMembers.any { (id, _) -> !assignedMemberIds.contains(id) }

    val canConfirm = when (mode) {
        CompletionDialogMode.Single -> singleSelectedId.isNotEmpty()
        CompletionDialogMode.Multi -> multiSelectedIds.isNotEmpty()
    }

    // Sub-modal DatePicker.
    if (showDatePickerSub) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePickerSub = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { selectedDateMillis = it }
                    showDatePickerSub = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerSub = false }) { Text("Annuler") }
            },
        ) { DatePicker(state = dpState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = when (mode) {
                        CompletionDialogMode.Single -> "Deja fait"
                        CompletionDialogMode.Multi -> "Fait ensemble"
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // --- Date ---
                OutlinedButton(
                    onClick = { showDatePickerSub = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.width(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(formatDateFr(selectedDateMillis))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Participants ---
                Text(
                    text = when (mode) {
                        CompletionDialogMode.Single -> "Qui a fait ?"
                        CompletionDialogMode.Multi -> "Qui a participe ?"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))

                visibleMembers.forEach { (id, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (mode) {
                                    CompletionDialogMode.Single -> singleSelectedId = id
                                    CompletionDialogMode.Multi -> {
                                        multiSelectedIds = if (multiSelectedIds.contains(id))
                                            multiSelectedIds - id
                                        else
                                            multiSelectedIds + id
                                    }
                                }
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        when (mode) {
                            CompletionDialogMode.Single -> RadioButton(
                                selected = singleSelectedId == id,
                                onClick = { singleSelectedId = id },
                            )
                            CompletionDialogMode.Multi -> Checkbox(
                                checked = multiSelectedIds.contains(id),
                                onCheckedChange = { checked ->
                                    multiSelectedIds = if (checked) multiSelectedIds + id
                                    else multiSelectedIds - id
                                },
                            )
                        }
                        Text(name)
                    }
                }

                if (hasHiddenMembers) {
                    TextButton(onClick = { showAllMembers = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ajouter un membre")
                    }
                }

                if (mode == CompletionDialogMode.Single
                    && onSwitchToMulti != null
                    && allMembers.size > 1
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSwitchToMulti) {
                        Icon(Icons.Default.Groups, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("A plusieurs...")
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Annuler") }
                Button(
                    enabled = canConfirm,
                    onClick = {
                        val participants = when (mode) {
                            CompletionDialogMode.Single -> listOf(singleSelectedId)
                            CompletionDialogMode.Multi -> multiSelectedIds.toList()
                        }
                        onConfirm(participants, millisToIsoNoonUtc(selectedDateMillis))
                    },
                ) { Text("Valider") }
            }
        },
        dismissButton = {},
    )
}

/** Formate "15 avril 2026" pour affichage FR. */
private fun formatDateFr(millis: Long): String {
    val date = Instant.ofEpochMilli(millis)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    val today = LocalDate.now(ZoneOffset.UTC)
    val yesterday = today.minusDays(1)
    return when (date) {
        today -> "Aujourd'hui"
        yesterday -> "Hier"
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
    }
}

/** Convertit des millis en ISO 8601 a midi UTC (evite les surprises TZ). */
private fun millisToIsoNoonUtc(millis: Long): String {
    val date = Instant.ofEpochMilli(millis)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    return "${date}T12:00:00Z"
}

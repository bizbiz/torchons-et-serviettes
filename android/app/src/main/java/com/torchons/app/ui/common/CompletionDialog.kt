package com.torchons.app.ui.common

import android.content.res.Resources
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.CompletionPreview
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
    onConfirm: (participantIds: List<String>, isoDate: String, preview: CompletionPreview?) -> Unit,
    onSwitchToMulti: (() -> Unit)? = null,
    /**
     * Fournisseur de preview des points (bonus/malus). null = offline ou preview
     * indisponible — on affiche un fallback "≈ X pts". Appele a chaque changement
     * de date ou de nombre de participants.
     */
    previewProvider: (suspend (isoDate: String, numParticipants: Int) -> CompletionPreview?)? = null,
    /** v0.32 : si false, masque la ligne "Tu gagneras X pts" (l'user n'a pas
     *  de gamification pour ce foyer). La preview est quand meme calculee
     *  cote backend mais non affichee — re-toggler la rend visible. */
    gamificationVisible: Boolean = true,
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

    // Preview points (bonus/malus) : fetch a chaque changement de date /
    // nombre de participants. Releve ici pour etre accessible dans onConfirm.
    val numParticipants = when (mode) {
        CompletionDialogMode.Single -> 1
        CompletionDialogMode.Multi -> multiSelectedIds.size.coerceAtLeast(1)
    }
    val isoDateForPreview = remember(selectedDateMillis) {
        millisToIsoNoonUtc(selectedDateMillis)
    }
    var preview by remember { mutableStateOf<CompletionPreview?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    if (previewProvider != null) {
        LaunchedEffect(isoDateForPreview, numParticipants) {
            previewFailed = false
            preview = try {
                previewProvider(isoDateForPreview, numParticipants)
            } catch (_: Exception) {
                previewFailed = true
                null
            }
            if (preview == null) previewFailed = true
        }
    }

    val res = LocalContext.current.resources

    // Sub-modal DatePicker.
    if (showDatePickerSub) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePickerSub = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { selectedDateMillis = it }
                    showDatePickerSub = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerSub = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) { DatePicker(state = dpState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = when (mode) {
                        CompletionDialogMode.Single -> stringResource(R.string.completion_title_single)
                        CompletionDialogMode.Multi -> stringResource(R.string.completion_title_multi)
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
                    Text(formatDateLocalized(res, selectedDateMillis))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Participants ---
                Text(
                    text = when (mode) {
                        CompletionDialogMode.Single -> stringResource(R.string.completion_picker_who_single)
                        CompletionDialogMode.Multi -> stringResource(R.string.completion_picker_who_multi)
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
                        Text(stringResource(R.string.completion_add_member))
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
                        Text(stringResource(R.string.completion_switch_to_multi))
                    }
                }

                // --- Preview points (bonus/malus) ---
                // v0.32 : ligne points cachee si gamification desactivee.
                if (previewProvider != null && gamificationVisible) {
                    Spacer(modifier = Modifier.height(12.dp))
                    PreviewPointsLine(preview = preview, failed = previewFailed)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = canConfirm,
                    onClick = {
                        val participants = when (mode) {
                            CompletionDialogMode.Single -> listOf(singleSelectedId)
                            CompletionDialogMode.Multi -> multiSelectedIds.toList()
                        }
                        onConfirm(
                            participants,
                            millisToIsoNoonUtc(selectedDateMillis),
                            preview,
                        )
                    },
                ) { Text(stringResource(R.string.common_validate)) }
            }
        },
        dismissButton = {},
    )
}

/**
 * Formate la date selon la locale courante (ex: "15 avril 2026" en FR,
 * "April 15, 2026" en EN), avec raccourcis "Aujourd'hui"/"Hier" pour
 * les 2 dates les plus communes.
 */
private fun formatDateLocalized(res: Resources, millis: Long): String {
    val date = Instant.ofEpochMilli(millis)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    val today = LocalDate.now(ZoneOffset.UTC)
    val yesterday = today.minusDays(1)
    return when (date) {
        today -> res.getString(R.string.completion_date_today)
        yesterday -> res.getString(R.string.completion_date_yesterday)
        else -> date.format(
            DateTimeFormatter.ofPattern("d MMMM yyyy", res.configuration.locales.get(0) ?: Locale.getDefault())
        )
    }
}

/** Convertit des millis en ISO 8601 a midi UTC (evite les surprises TZ). */
private fun millisToIsoNoonUtc(millis: Long): String {
    val date = Instant.ofEpochMilli(millis)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    return "${date}T12:00:00Z"
}

/**
 * Ligne "Tu gagneras X pts" + raison (bonus/malus/neutre/1ere/perso).
 * Affichee au bas du dialog de completion, dynamique selon date et N
 * participants. Backend authoritative via /completion-preview.
 */
@Composable
private fun PreviewPointsLine(
    preview: CompletionPreview?,
    failed: Boolean,
) {
    if (preview == null) {
        // Loader / fallback.
        Text(
            text = if (failed) stringResource(R.string.completion_preview_unavailable)
            else stringResource(R.string.completion_preview_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val (emoji, reasonLabel, reasonColor) = when (preview.reason) {
        "BONUS_RETARD" -> Triple("🔥", stringResource(R.string.completion_preview_reason_bonus_late), Color(0xFF2E7D32))
        "MALUS_AVANCE" -> Triple("⏱", stringResource(R.string.completion_preview_reason_malus_early), Color(0xFFEF6C00))
        "FIRST_COMPLETION" -> Triple("✨", stringResource(R.string.completion_preview_reason_first), Color(0xFF1976D2))
        "PERSO" -> Triple(null, null, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(null, null, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    var showSoloInfo by remember { mutableStateOf(false) }
    val isSolo = preview.reason == "PERSO" || preview.finalPoints == 0

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(
                    R.plurals.completion_preview_earn,
                    preview.finalPoints,
                    preview.finalPoints,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = reasonColor,
            )
            // Icone (i) cliquable pour expliquer le 0 pt des taches solo.
            if (isSolo) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.completion_preview_solo_info_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { showSoloInfo = true },
                )
            }
        }
        if (reasonLabel != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (emoji != null) {
                    Text(text = emoji, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                val delta = preview.finalPoints - preview.basePoints
                val deltaLabel = when {
                    delta > 0 -> pluralStringResource(
                        R.plurals.completion_preview_delta_positive,
                        delta,
                        delta,
                        reasonLabel,
                    )
                    delta < 0 -> stringResource(
                        R.string.completion_preview_delta_negative_format,
                        delta,
                        reasonLabel,
                    )
                    else -> reasonLabel
                }
                Text(
                    text = deltaLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = reasonColor,
                )
            }
        }
    }

    if (showSoloInfo) {
        SoloPointsInfoDialog(onDismiss = { showSoloInfo = false })
    }
}

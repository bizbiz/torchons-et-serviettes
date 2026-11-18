package com.torchons.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.SoloPointsInfoDialog
import com.torchons.app.ui.common.rememberCelebration
import com.torchons.app.ui.common.taskStateColor
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.TaskUrgencyCalculator

// Moved out of HomeScreen on 2026-11-18 (file at its size cap). Also draws the red
// « À faire aujourd'hui » stack's card (HomeTodayStack.kt): same validation buttons.

@Composable
internal fun SuggestionCard(
    suggestion: Suggestion,
    room: TaskContextRef?,
    category: TaskContextRef?,
    lastCompletedAt: String? = null,
    isCelebrating: Boolean,
    celebrationPoints: Int? = null,
    celebrationReason: String? = null,
    /** v0.32 : si false, masque la pastille bonus, la ligne "💰 N pts" et
     *  le "+X pts" dans la celebration. Le reste (titre, countdown, piece,
     *  bouton "Fait !") reste inchange. */
    gamificationVisible: Boolean = true,
    /** Per-participant attribution shown in the celebration (name, points). */
    celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /** Household members as (userId, displayName) pairs, deduplicated. */
    members: List<Pair<String, String>> = emptyList(),
    currentUserId: String = "",
    /** Server points preview for (isoDate, numParticipants); null offline. */
    previewProvider: suspend (isoDate: String, numParticipants: Int) -> com.torchons.app.data.model.CompletionPreview? =
        { _, _ -> null },
    onClick: () -> Unit,
    /** Immediate completion by the current user, dated now (J-0 path). */
    onCompleteToday: (previewPoints: Int?, previewReason: String?) -> Unit,
    /** Backdated single completion; completedBy null = current user. */
    onCompleteAtDate: (isoDate: String, completedBy: String?, previewPoints: Int?, previewReason: String?) -> Unit,
    /** Collective completion by the given participants at the given date. */
    onCompleteCollective: (participantIds: List<String>, isoDate: String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit = {},
    /** Reason `today` (the red stack): its header (« À faire aujourd'hui · 1 sur 3 »). */
    todayHeader: String? = null,
    /** Reason `today`: « Confiée par Alice », under the title. */
    todaySubline: String? = null,
    /** No cadence (one-off, « uncertain »): its countdown is grey, never green (TaskStateColor.kt). */
    withoutCadence: Boolean = suggestion.reason == "uncertain",
) {
    val reasonLabel = when (suggestion.reason) {
        "urgent" -> stringResource(R.string.suggestion_reason_urgent)
        "uncertain" -> stringResource(R.string.suggestion_reason_uncertain)
        TODAY_REASON -> todayHeader ?: stringResource(R.string.today_title)
        else -> stringResource(R.string.suggestion_reason_affinity)
    }
    val reasonIcon = when (suggestion.reason) {
        "urgent" -> Icons.Default.Warning
        "uncertain" -> Icons.Default.Refresh
        TODAY_REASON -> Icons.Default.PriorityHigh
        else -> Icons.Default.Favorite
    }
    val reasonColor = when (suggestion.reason) {
        "urgent", TODAY_REASON -> TaskUrgencyCalculator.ROUGE
        else -> TaskUrgencyCalculator.VERT
    }
    // Pickee une seule fois pour cette celebration (key suggestion.taskId).
    val celebration = if (isCelebrating) rememberCelebration(suggestion.taskId) else null

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        // Mode celebration : on remplace tout le contenu par les emoji de
        // felicitations. Le confetti est gere au niveau du Box parent.
        if (celebration != null) {
            CelebrationCardContent(
                emoji = celebration.first,
                text = celebration.second,
                points = celebrationPoints,
                reason = celebrationReason,
                earnedBy = celebrationEarnedBy,
            )
            return@Card
        }
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge raison + pastille bonus + bouton edit
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = reasonIcon,
                    contentDescription = null,
                    tint = reasonColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = reasonColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (gamificationVisible && suggestion.bonusEligible && suggestion.bonusPct > 0f) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFFE8D6))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.suggestion_bonus_badge_format, (suggestion.bonusPct * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC2410C),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.suggestion_edit_cd),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // v0.16+ : ignorer cette suggestion (session only, reset au
                // cold start). La suivante du meme tier prend la place.
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.suggestion_dismiss_cd),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            todaySubline?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskUrgencyCalculator.ROUGE,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Countdown text (dans Nj / aujourd'hui / en retard de Nj). Couleur
            // selon l'etat (vert/orange/rouge) renvoye par /api/suggestions.
            // Pour uncertain (v0.14) : libelle factuel "Fait il y a X j" au lieu
            // du countdown (pas de notion de retard, Q2/Q9).
            val urgencyRes = androidx.compose.ui.platform.LocalContext.current.resources
            val countdownLabel = if (suggestion.reason == "uncertain") {
                TaskUrgencyCalculator.uncertainCountdownLabel(
                    res = urgencyRes,
                    lastCompletedAt = lastCompletedAt,
                    today = java.time.LocalDate.now(),
                )
            } else {
                TaskUrgencyCalculator.countdownLabelFromDays(urgencyRes, suggestion.daysUntilDue)
            }
            if (countdownLabel.isNotEmpty()) {
                Text(
                    text = countdownLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = taskStateColor(suggestion.state, withoutCadence),
                    fontWeight = FontWeight.Medium,
                )
            }

            // Piece + categorie (ligne discrete sous le titre)
            TaskMetaRow(room = room, category = category)

            Spacer(modifier = Modifier.height(12.dp))

            // Completion controls state — declared before the points line
            // because the predicted points follow the selected date. One
            // selectedDate per card, shared by both buttons; keyed on taskId
            // so it resets to "today / everyone" whenever the suggested task
            // changes or the screen leaves composition.
            val today = java.time.LocalDate.now()
            var selectedDate by remember(suggestion.taskId) { mutableStateOf(today) }
            // Defensive clamp if the app crossed midnight while in memory.
            if (selectedDate.isAfter(today)) selectedDate = today
            val memberIds = remember(members) { members.map { it.first } }
            var selectedParticipantIds by remember(suggestion.taskId, memberIds) {
                mutableStateOf(memberIds.toSet())
            }
            var showDatePicker by remember(suggestion.taskId) { mutableStateOf(false) }
            var showParticipantsMenu by remember(suggestion.taskId) { mutableStateOf(false) }

            val dayOffset = java.time.temporal.ChronoUnit.DAYS
                .between(selectedDate, today).toInt()
            val dayLabel = CompletionDateLabels.relativeDayLabel(urgencyRes, selectedDate, today)

            // Date-aware points preview: backdating changes bonus/malus, so
            // the points line must follow the selected date (a late bonus
            // disappears when the completion is dated back before the due
            // date). J-0 keeps the /suggestions prediction; null on fetch
            // failure (offline) falls back to it too.
            var datePreview by remember(suggestion.taskId) {
                mutableStateOf<com.torchons.app.data.model.CompletionPreview?>(null)
            }
            LaunchedEffect(suggestion.taskId, selectedDate) {
                datePreview = if (dayOffset == 0) null
                else previewProvider(CompletionDateLabels.toIsoNoonUtc(selectedDate), 1)
            }
            // Best-known single-completion points for the current selection,
            // passed to the celebration so it can attribute exact points.
            val singlePreviewPoints = datePreview?.finalPoints
                ?: suggestion.predictedFinalPoints.takeIf { dayOffset == 0 }
            val singlePreviewReason = datePreview?.reason
                ?: "BONUS_RETARD".takeIf {
                    dayOffset == 0 && suggestion.bonusEligible &&
                        suggestion.predictedFinalPoints > suggestion.predictedBasePoints
                }

            // Ligne points predits : "💰 N pts" (+ delta bonus si applicable).
            // Pour les taches solo (predicted = 0) : "💰 0 pt" + icone (i)
            // cliquable qui ouvre SoloPointsInfoDialog (pedago "pourquoi 0 ?").
            // Cachee en mode opt-out (v0.32).
            if (gamificationVisible) {
                SuggestionPointsLine(suggestion = suggestion, preview = datePreview)
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Left slot: -1 day arrow, becomes a calendar shortcut past 15
            // days back. Right slot: +1 day arrow, calendar shortcut at J-0
            // (there is never a future date to navigate to).
            val atCalendarDepth = dayOffset >= MAX_ARROW_NAV_DAYS
            val leftIcon =
                if (atCalendarDepth) Icons.Default.CalendarMonth
                else Icons.AutoMirrored.Filled.KeyboardArrowLeft
            val leftCd = stringResource(
                if (atCalendarDepth) R.string.home_date_open_calendar_cd
                else R.string.home_date_prev_day_cd
            )
            val onLeftSlot: () -> Unit = {
                if (dayOffset >= MAX_ARROW_NAV_DAYS) showDatePicker = true
                else selectedDate = selectedDate.minusDays(1)
            }
            val rightIcon =
                if (dayOffset == 0) Icons.Default.CalendarMonth
                else Icons.AutoMirrored.Filled.KeyboardArrowRight
            val rightCd = stringResource(
                if (dayOffset == 0) R.string.home_date_open_calendar_cd
                else R.string.home_date_next_day_cd
            )
            val onRightSlot: () -> Unit = {
                if (dayOffset == 0) showDatePicker = true
                else selectedDate = selectedDate.plusDays(1)
            }

            // Button 1 — "Done <day>": immediate completion by me.
            SegmentedCompletionButton(
                centerLabel = stringResource(R.string.home_done_day_format, dayLabel),
                leftIcon = leftIcon,
                leftContentDescription = leftCd,
                onLeftClick = onLeftSlot,
                rightIcon = rightIcon,
                rightContentDescription = rightCd,
                onRightClick = onRightSlot,
                onCenterClick = {
                    if (dayOffset == 0) {
                        onCompleteToday(singlePreviewPoints, singlePreviewReason)
                    } else {
                        onCompleteAtDate(
                            CompletionDateLabels.toIsoNoonUtc(selectedDate),
                            null,
                            singlePreviewPoints,
                            singlePreviewReason,
                        )
                    }
                },
                filled = true,
            )

            // Button 2 — "Together <day>" with participant chevron. Hidden
            // for single-member households (no one to share with).
            if (members.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))

                val allSelected = selectedParticipantIds.size == memberIds.size
                val centerLabel = when {
                    allSelected -> stringResource(R.string.home_together_day_format, dayLabel)
                    selectedParticipantIds.size == 1 -> stringResource(
                        R.string.home_done_by_one_day_format,
                        members.firstOrNull { it.first == selectedParticipantIds.first() }?.second.orEmpty(),
                        dayLabel,
                    )
                    else -> pluralStringResource(
                        R.plurals.home_done_by_people_day,
                        selectedParticipantIds.size,
                        selectedParticipantIds.size,
                        dayLabel,
                    )
                }
                SegmentedCompletionButton(
                    centerLabel = centerLabel,
                    leftIcon = leftIcon,
                    leftContentDescription = leftCd,
                    onLeftClick = onLeftSlot,
                    rightIcon = rightIcon,
                    rightContentDescription = rightCd,
                    onRightClick = onRightSlot,
                    onCenterClick = {
                        val iso = CompletionDateLabels.toIsoNoonUtc(selectedDate)
                        if (selectedParticipantIds.size == 1) {
                            onCompleteAtDate(
                                iso,
                                selectedParticipantIds.first(),
                                singlePreviewPoints,
                                singlePreviewReason,
                            )
                        } else {
                            // Collective: the server response corrects the
                            // celebration with the exact per-participant split.
                            onCompleteCollective(selectedParticipantIds.toList(), iso)
                        }
                    },
                    filled = false,
                    chevronContentDescription = stringResource(R.string.home_participants_chevron_cd),
                    onChevronClick = if (members.size > 1) ({ showParticipantsMenu = true }) else null,
                    chevronMenu = {
                        ParticipantsMenu(
                            expanded = showParticipantsMenu,
                            members = members,
                            currentUserId = currentUserId,
                            selectedIds = selectedParticipantIds,
                            onSelectionChange = { selectedParticipantIds = it },
                            onDismiss = { showParticipantsMenu = false },
                        )
                    },
                )
            }

            if (showDatePicker) {
                PastOnlyDatePickerDialog(
                    initialDate = selectedDate,
                    onDateSelected = {
                        selectedDate = it.coerceAtMost(today)
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false },
                )
            }
        }
    }
}

/** Reason of the cards of the red « À faire aujourd'hui » stack (HomeTodayStack.kt). */
internal const val TODAY_REASON = "today"

package com.torchons.app.ui.taskdetail

import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.model.resolveMemberLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.RecurrenceTypes
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.TaskHistoryEventType
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.CelebrationOverlay
import com.torchons.app.ui.common.CompletionDialog
import com.torchons.app.ui.common.CompletionDialogMode
import com.torchons.app.ui.common.RatingDialog
import com.torchons.app.ui.common.anniversaryPeriodLabel
import com.torchons.app.ui.user.UserProfileDialog
import com.torchons.app.ui.user.UserProfileRequest
import com.torchons.app.util.DurationFormat
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Groupe de completions partageant un collective_id (1 ligne dans l'UI = 1
 * evenement). `head` est la premiere ligne triee chronologique desc — sert
 * de reference pour la date, le score, les notes. `participantIds` enumere
 * les participants dans l'ordre de saisie.
 */
data class CompletionGroup(
    val key: String,
    val collectiveId: String?,
    val head: Completion,
    val rows: List<Completion>,
)

/** Joint une liste de noms en francais : "A, B & C" — virgules + esperluette finale. */
private fun joinWithAmp(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
}

/**
 * Regroupe une liste de Completion par collective_id. Les lignes solo gardent
 * leur cle individuelle. Le head de chaque groupe = la row dont l'id est
 * minimal lexicalement (deterministe ; toutes partagent le meme completed_at).
 */
internal fun groupCompletions(completions: List<Completion>): List<CompletionGroup> {
    val grouped = LinkedHashMap<String, MutableList<Completion>>()
    for (c in completions) {
        val key = c.collectiveId ?: "solo:${c.id}"
        grouped.getOrPut(key) { mutableListOf() }.add(c)
    }
    return grouped.map { (key, rows) ->
        val sorted = rows.sortedBy { it.id }
        CompletionGroup(
            key = key,
            collectiveId = sorted.first().collectiveId,
            head = sorted.first(),
            rows = sorted,
        )
    }
}

/**
 * Carte affichant une completion (ou un groupe collectif) dans l'historique.
 * Solo : tap = edit, long-press = delete-one. Collectif : tap = edit head,
 * long-press = delete-collective (toutes les lignes du collective_id).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompletionCard(
    group: CompletionGroup,
    userNames: Map<String, String>,
    formerMembers: Map<String, FormerMember> = emptyMap(),
    onDeleteOne: () -> Unit,
    onDeleteCollective: () -> Unit,
    onEdit: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    /** False when the household (or the user) turned gamification off: no « +1 pt ». */
    showPoints: Boolean = true,
) {
    val isCollective = group.collectiveId != null && group.rows.size >= 2
    val head = group.head
    val formerLabel = stringResource(R.string.former_member)
    // Active name, struck-through name (left, return window open) or "former member".
    fun labelOf(id: String) = resolveMemberLabel(id, userNames, formerMembers, formerLabel)
    val participantNames = group.rows.map { labelOf(it.completedBy).text }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (isCollective) stringResource(R.string.task_detail_delete_collective_title)
                    else stringResource(R.string.task_detail_delete_completion_title)
                )
            },
            text = {
                if (isCollective) {
                    Text(
                        stringResource(
                            R.string.task_detail_delete_collective_message_format,
                            joinWithAmp(participantNames),
                            formatDateTime(head.completedAt),
                        )
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.task_detail_delete_completion_message_format,
                            formatDateTime(head.completedAt),
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        if (isCollective) onDeleteCollective() else onDeleteOne()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEdit,
                onLongClick = { showDeleteDialog = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icone : check pour solo, groupe pour collectif.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(TaskUrgencyCalculator.VERT.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCollective) Icons.Default.Groups else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TaskUrgencyCalculator.VERT
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // AnnotatedString unique : noms cliquables (couleur primaire +
                // souligne) + date inline. Une seule ligne de texte → baseline
                // coherente entre les noms et le reste, plus de decalage vertical.
                val primaryColor = MaterialTheme.colorScheme.primary
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val rowsForKey = group.rows
                // Pré-calcul dans le scope @Composable, formatDateTime étant lui-même @Composable.
                val dateLabel = formatDateTime(head.completedAt)
                val annotated = remember(
                    rowsForKey, userNames, formerMembers, formerLabel,
                    dateLabel, primaryColor, onSurfaceColor,
                ) {
                    buildAnnotatedString {
                        addStyle(SpanStyle(color = onSurfaceColor), 0, 0)
                        if (isCollective) {
                            rowsForKey.forEachIndexed { idx, row ->
                                val label = labelOf(row.completedBy)
                                if (idx > 0) {
                                    append(if (idx == rowsForKey.size - 1) " & " else ", ")
                                }
                                val start = length
                                append(label.text)
                                addStyle(nameStyle(label.struck, primaryColor), start, length)
                                // A member who left has no profile to open.
                                if (row.completedBy in userNames) {
                                    addStringAnnotation("user", row.completedBy, start, length)
                                }
                            }
                        } else {
                            val label = labelOf(head.completedBy)
                            val start = length
                            append(label.text)
                            addStyle(nameStyle(label.struck, primaryColor), start, length)
                            if (head.completedBy in userNames) {
                                addStringAnnotation("user", head.completedBy, start, length)
                            }
                        }
                        append(" — $dateLabel")
                    }
                }
                ClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(color = onSurfaceColor),
                    onClick = { offset ->
                        val ann = annotated.getStringAnnotations(
                            tag = "user", start = offset, end = offset
                        ).firstOrNull()
                        if (ann != null) {
                            onUserClick(ann.item)
                        } else {
                            onEdit()
                        }
                    },
                )

                if (!head.notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = head.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Points gagnes : "+X pt" solo / "+X pt chacun" collectif.
            if (showPoints) Text(
                text = if (isCollective)
                    pluralStringResource(R.plurals.task_detail_points_collective, head.pointsEarned, head.pointsEarned)
                else pluralStringResource(R.plurals.task_detail_points_solo, head.pointsEarned, head.pointsEarned),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Clickable author name: underlined, or struck through when the member left. */
private fun nameStyle(struck: Boolean, color: androidx.compose.ui.graphics.Color) = SpanStyle(
    color = color,
    fontWeight = FontWeight.SemiBold,
    textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.Underline,
)

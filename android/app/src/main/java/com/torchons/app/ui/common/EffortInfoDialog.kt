package com.torchons.app.ui.common

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.EffortVotesState
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Modal "+ d'info" sur l'effort d'une tache (v0.29).
 *
 * Symetrique a FrequencyInfoDialog : pedago courte sur le mecanisme du
 * vote effort (points, fair-share, retard foyer), liste par voteur avec
 * "Modifier mon vote" / "Demander revote", section non-votants avec
 * "Demander a voter".
 *
 * @param foyerMembers liste des (user_id, display_name) du foyer assignes
 *   a la tache. Permet de calculer les non-votants.
 * @param currentUserId id de l'user courant pour distinguer "ma ligne".
 * @param onAskRevote callback pour demander a un user de revoter (kind=effort).
 * @param onEditMyVote callback pour ouvrir EffortVoteDialog.
 */
@Composable
fun EffortInfoDialog(
    state: EffortVotesState,
    foyerMembers: List<Pair<String, String>>,
    currentUserId: String?,
    onAskRevote: (targetUserId: String) -> Unit,
    onEditMyVote: () -> Unit,
    onDismiss: () -> Unit,
    /** v0.32 : si false (gamif desactivee user OU foyer), retire les mentions
     *  de points / fair-share du texte pedago — focus sur "temps moyen". */
    gamificationVisible: Boolean = true,
) {
    val res = LocalContext.current.resources
    // Set des user_ids ayant vote (moi inclus si myPoints != null).
    val voterIds = buildSet {
        state.myPoints?.let { currentUserId?.let { id -> add(id) } }
        addAll(state.others.map { it.userId })
    }
    val nonVoters = foyerMembers.filter { it.first !in voterIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.effort_info_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (gamificationVisible) stringResource(R.string.effort_info_message_gamif)
                    else stringResource(R.string.effort_info_message_no_gamif),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Mon vote (si vote)
                if (state.myPoints != null && currentUserId != null) {
                    EffortVoteRow(
                        name = stringResource(R.string.vote_info_me_label),
                        points = state.myPoints,
                        updatedAt = state.myUpdatedAt,
                        isMe = true,
                        actionButton = {
                            OutlinedButton(onClick = onEditMyVote) {
                                Text(
                                    stringResource(R.string.vote_info_button_edit_my),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                }

                // Autres votes
                state.others.forEach { other ->
                    val isPending = other.userId in state.pendingRevoteUserIds
                    EffortVoteRow(
                        name = other.displayName,
                        points = other.points,
                        updatedAt = other.updatedAt,
                        isMe = false,
                        actionButton = {
                            OutlinedButton(
                                onClick = { onAskRevote(other.userId) },
                                enabled = !isPending,
                            ) {
                                Text(
                                    text = if (isPending) stringResource(R.string.vote_info_button_request_pending)
                                    else stringResource(R.string.vote_info_button_ask_revote),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // Non-votants
                if (nonVoters.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.vote_info_no_votes_yet),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    nonVoters.forEach { (uid, name) ->
                        val isPending = uid in state.pendingRevoteUserIds
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Box(modifier = Modifier.width(8.dp))
                            Spacer(modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { onAskRevote(uid) },
                                enabled = !isPending,
                            ) {
                                Text(
                                    text = if (isPending) stringResource(R.string.vote_info_button_request_pending)
                                    else stringResource(R.string.vote_info_button_ask_to_vote),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun EffortVoteRow(
    name: String,
    points: Int,
    updatedAt: String?,
    isMe: Boolean,
    actionButton: @Composable () -> Unit,
) {
    val res = LocalContext.current.resources
    val summary = effortPointsToTime(points)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = summary + (formatVoteDate(res, updatedAt)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        actionButton()
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

internal fun formatVoteDate(res: Resources, iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val odt = OffsetDateTime.parse(iso)
        res.getString(R.string.vote_info_voted_on_format, odt.format(DATE_FMT))
    } catch (_: Exception) {
        null
    }
}

package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.util.DurationFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Modal "+ d'info" sur la frequence d'une tache (v0.28).
 *
 * Contenu (decisions /grill-me Q9) :
 * - F1 : pedago courte sur le mecanisme du vote/consensus.
 * - G3 : ligne par voteur (nom + valeur + date) avec bouton "Modifier mon
 *   vote" (sur ma ligne) ou "Demander revote" (autres). Bouton desactive
 *   si pending (J1).
 * - H1 : lignes pour non-votants avec bouton "Demander a voter".
 * - K2 : monthly_variable -> tap pour deplier les 12 valeurs.
 *
 * @param foyerMembers liste des (user_id, display_name) du foyer assignes
 *   a la tache. Permet de calculer les non-votants.
 * @param currentUserId id de l'user courant pour distinguer "ma ligne".
 * @param onAskRevote callback pour demander a un user de revoter.
 * @param onEditMyVote callback pour ouvrir FrequencyVoteDialog.
 */
@Composable
fun FrequencyInfoDialog(
    state: FrequencyVotesState,
    foyerMembers: List<Pair<String, String>>,
    currentUserId: String?,
    onAskRevote: (targetUserId: String) -> Unit,
    onEditMyVote: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Etat local : quels votes "Selon le mois" sont deplies (cle = user_id).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // Set des user_ids ayant vote.
    val voterIds = buildSet {
        state.myDaysPerMonth?.let { currentUserId?.let { id -> add(id) } }
        addAll(state.others.map { it.userId })
    }
    val nonVoters = foyerMembers.filter { it.first !in voterIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frequence : comment ca marche ?") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // F1 — pedago
                Text(
                    text = "La frequence affichee est la moyenne des votes de chaque membre du foyer. Plus il y a de votes, plus elle est representative. Tu peux ajuster ton vote ou demander a un autre membre de revoter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Mon vote (si vote)
                if (state.myDaysPerMonth != null && currentUserId != null) {
                    VoteRow(
                        name = "Toi",
                        daysPerMonth = state.myDaysPerMonth,
                        updatedAt = state.myUpdatedAt,
                        isMe = true,
                        isExpanded = expanded[currentUserId] ?: false,
                        onToggleExpand = { expanded[currentUserId] = !(expanded[currentUserId] ?: false) },
                        actionButton = {
                            OutlinedButton(onClick = onEditMyVote) {
                                Text("Modifier mon vote", style = MaterialTheme.typography.labelSmall)
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
                    VoteRow(
                        name = other.displayName,
                        daysPerMonth = other.daysPerMonth,
                        updatedAt = other.updatedAt,
                        isMe = false,
                        isExpanded = expanded[other.userId] ?: false,
                        onToggleExpand = { expanded[other.userId] = !(expanded[other.userId] ?: false) },
                        actionButton = {
                            OutlinedButton(
                                onClick = { onAskRevote(other.userId) },
                                enabled = !isPending,
                            ) {
                                Text(
                                    text = if (isPending) "Demande envoyee" else "Demander revote",
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
                        text = "Pas encore vote",
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
                                    text = if (isPending) "Demande envoyee" else "Demander a voter",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}

@Composable
private fun VoteRow(
    name: String,
    daysPerMonth: Map<String, Int>,
    updatedAt: String?,
    isMe: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    actionButton: @Composable () -> Unit,
) {
    val values = daysPerMonth.values.toList()
    val isVariable = values.isNotEmpty() && values.any { it != values.first() }
    val summary = if (isVariable) "variable selon le mois"
    else values.firstOrNull()?.let { "tous les ${DurationFormat.formatDaysHuman(it)}" } ?: "?"

    Column(modifier = Modifier.fillMaxWidth()) {
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
                    text = summary + (formatVoteDate(updatedAt)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            actionButton()
        }
        if (isVariable) {
            TextButton(onClick = onToggleExpand) {
                Text(
                    text = if (isExpanded) "Masquer le detail" else "Voir les 12 mois",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (isExpanded) {
                MonthDetailGrid(daysPerMonth)
            }
        }
    }
}

@Composable
private fun MonthDetailGrid(daysPerMonth: Map<String, Int>) {
    val labels = listOf("Jan", "Fev", "Mar", "Avr", "Mai", "Juin", "Jul", "Aou", "Sep", "Oct", "Nov", "Dec")
    Column {
        // 4 colonnes x 3 rangees
        for (row in 0 until 3) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (col in 0 until 4) {
                    val idx = row * 4 + col
                    val month = (idx + 1).toString()
                    val v = daysPerMonth[month] ?: 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                    ) {
                        Text(
                            text = "${labels[idx]}: ${v}j",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun formatVoteDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val odt = OffsetDateTime.parse(iso)
        "vote le ${odt.format(DATE_FMT)}"
    } catch (_: Exception) {
        null
    }
}

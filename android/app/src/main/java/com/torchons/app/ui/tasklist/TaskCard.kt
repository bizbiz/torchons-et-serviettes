package com.torchons.app.ui.tasklist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.CelebrationCardContent
import com.torchons.app.ui.common.randomCelebration
import com.torchons.app.util.TaskUrgencyCalculator

/**
 * Carte tache simplifiee — version v0.5.5/v0.5.6.
 *
 * Affiche uniquement les infos essentielles : titre + piece + indicateur
 * de retard (bordure verticale gauche couleur freshness).
 *
 * IMPORTANT cote perf : les callbacks prennent un String (taskId) et NON
 * une lambda sans argument. Ca permet au caller de passer des references
 * stables (method ref ou val remember) plutot que de creer une nouvelle
 * lambda par item a chaque recompose, ce qui ferait recomposer tous les
 * items visibles a chaque emission du combine du ViewModel.
 *
 * La piece est extraite du titre (pattern "Nom — Piece") jusqu'a la refonte
 * en v0.6.0 ou elle deviendra une vraie colonne.
 */
@Composable
fun TaskCard(
    taskWithUrgency: TaskWithUrgency,
    isCelebrating: Boolean,
    onClick: (String) -> Unit,
    onComplete: (String) -> Unit,
    onHistorical: (String) -> Unit,
    onFaitEnsemble: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Points affiches dans la celebration (null = inconnu / gamif masquee). */
    celebrationPoints: Int? = null,
    /** Raison bonus/malus de la celebration. */
    celebrationReason: String? = null,
    /** Attribution par participant (displayName, points) — "pour qui" les points. */
    celebrationEarnedBy: List<Pair<String, Int>>? = null,
) {
    val task = taskWithUrgency.task
    val taskId = task.id
    val stateColor = TaskUrgencyCalculator.stateColor(taskWithUrgency.state)
    val celebration = remember(taskId, isCelebrating) {
        if (isCelebrating) randomCelebration() else null
    }

    // Depuis v0.6.0 : le titre est propre (sans suffixe " — Piece"), la piece
    // vient directement de TaskWithUrgency.roomName / roomEmoji (pre-resolus
    // dans applyFilters via task.roomId).
    val displayTitle = task.title
    val roomLabel = taskWithUrgency.roomName?.let { name ->
        if (!taskWithUrgency.roomEmoji.isNullOrBlank()) {
            "${taskWithUrgency.roomEmoji} $name"
        } else {
            name
        }
    }

    Card(
        onClick = { onClick(taskId) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // Mode celebration : remplace tout le contenu pendant ~3s. Affiche les
        // points + l'attribution "pour qui" (parite avec l'Accueil).
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bordure verticale gauche coloree (etat d'urgence) — info
            // "en retard ou pas" en un coup d'oeil.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(stateColor),
            )

            // Contenu principal : titre + piece + countdown
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (roomLabel != null) {
                    Text(
                        text = roomLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (taskWithUrgency.countdownLabel.isNotEmpty()) {
                    Text(
                        text = taskWithUrgency.countdownLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = stateColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Boutons d'action — 3 boutons : deja fait (calendar), fait
            // ensemble (groups), fait (check). Taille reduite 40dp pour tenir
            // dans 72dp de hauteur avec 3 actions.
            FilledIconButton(
                onClick = { onHistorical(taskId) },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = stringResource(R.string.suggestion_action_already_done),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            FilledIconButton(
                onClick = { onFaitEnsemble(taskId) },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Groups,
                    contentDescription = stringResource(R.string.suggestion_action_together),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            FilledIconButton(
                onClick = { onComplete(taskId) },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = TaskUrgencyCalculator.VERT,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.suggestion_action_done),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

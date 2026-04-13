package com.torchons.app.ui.tasklist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.util.FreshnessCalculator

/**
 * Carte tache simplifiee — version v0.5.5.
 *
 * Affiche uniquement les infos essentielles : titre + piece + indicateur
 * de retard (bordure verticale gauche couleur freshness). Tous les autres
 * elements (badge categorie, etoiles weight, barre de progression, texte
 * "Rouge X%", pastille) ont ete retires pour gagner en sobriete et en
 * fluidite de scroll.
 *
 * La piece est extraite du titre (pattern "Nom — Piece") jusqu'a la refonte
 * en v0.6.0 ou elle deviendra une vraie colonne.
 */
@Composable
fun TaskCard(
    taskWithFreshness: TaskWithFreshness,
    onClick: () -> Unit,
    onComplete: (() -> Unit)? = null,
    onHistorical: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val task = taskWithFreshness.task
    val freshnessColor = FreshnessCalculator.getFreshnessColor(taskWithFreshness.freshness)

    // Separe le titre et la piece (pattern "Nom — Piece"). Si pas de
    // separateur, room = null et on affiche uniquement le titre brut.
    val titleAndRoom: Pair<String, String?> = remember(task.title) {
        val parts = task.title.split(" — ", limit = 2)
        if (parts.size == 2) parts[0] to parts[1].trim()
        else task.title to null
    }
    val displayTitle = titleAndRoom.first
    val room = titleAndRoom.second

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bordure verticale gauche colorée (couleur freshness) — donne
            // l'info "en retard ou pas" en un coup d'oeil.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(freshnessColor),
            )

            // Contenu principal : titre + piece
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (room != null) {
                    Text(
                        text = room,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Boutons d'action (taille v0.5.3)
            if (onHistorical != null) {
                FilledIconButton(
                    onClick = onHistorical,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "Deja fait",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (onComplete != null) {
                FilledIconButton(
                    onClick = onComplete,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = FreshnessCalculator.VERT,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Fait",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
    }
}

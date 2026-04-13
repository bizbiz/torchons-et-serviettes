package com.torchons.app.ui.tasklist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.util.FreshnessCalculator

/**
 * Carte affichant une tache dans la liste.
 * Montre le titre, la categorie, le poids, la barre de fraicheur
 * et l'initiale de l'utilisateur assigne.
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
    val freshness = taskWithFreshness.freshness
    val freshnessColor = FreshnessCalculator.getFreshnessColor(freshness)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Indicateur de fraicheur (cercle colore)
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(freshnessColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Contenu principal
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Titre de la tache
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Categorie et poids
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Badge de categorie
                    if (taskWithFreshness.categoryName != null) {
                        // Memoise le parsing de la couleur — sinon ce parse hexa
                        // s'execute a chaque recompose pour chaque carte visible.
                        val fallbackColor = MaterialTheme.colorScheme.primary
                        val catColor = remember(taskWithFreshness.categoryColor, fallbackColor) {
                            try {
                                Color(android.graphics.Color.parseColor(taskWithFreshness.categoryColor ?: "#6366F1"))
                            } catch (_: Exception) {
                                fallbackColor
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(catColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = taskWithFreshness.categoryName,
                                style = MaterialTheme.typography.labelSmall,
                                color = catColor
                            )
                        }
                    }

                    // Poids (etoiles)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(minOf(task.weight, 5)) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                        if (task.weight > 5) {
                            Text(
                                text = "+${task.weight - 5}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Barre de fraicheur
                LinearProgressIndicator(
                    progress = { (freshness / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = freshnessColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Libelle de fraicheur
                Text(
                    text = "${FreshnessCalculator.getFreshnessLabel(freshness)} (${freshness.toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = freshnessColor
                )
            }

            // Boutons actions — FilledIconButton avec background colore + bord
            // arrondi pour clairement signaler que c'est cliquable.
            Spacer(modifier = Modifier.width(8.dp))
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
            }
        }
    }
}

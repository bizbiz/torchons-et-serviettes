package com.torchons.app.ui.tasklist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Moved out of TaskListScreen on 2026-11-18 (file at its size cap), behaviour unchanged.

/**
 * Chip texte tri-state pour les filtres de la liste (v0.26).
 *
 * Cycle des etats au clic : OFF -> HIGH -> LOW -> OFF.
 *  - OFF  : libelle HIGH, sans fond, juste un contour leger. Indique
 *           ce que ferait un clic ("filtrer par X").
 *  - HIGH : libelle HIGH, fond rouge plein. Filtre actif positif.
 *  - LOW  : libelle LOW, fond vert plein. Filtre actif inverse.
 *
 * Le passage HIGH -> LOW change donc a la fois le fond ET le libelle,
 * pour que l'utilisateur lise directement ce qu'il filtre, sans avoir
 * a memoriser un code couleur d'icone.
 */
@Composable
internal fun FilterTextChip(
    highLabel: String,
    lowLabel: String,
    state: FilterState,
    onClick: () -> Unit,
) {
    val (bgColor, fgColor, showBorder) = when (state) {
        FilterState.OFF -> Triple(
            Color.Transparent,
            MaterialTheme.colorScheme.onSurfaceVariant,
            true,
        )
        FilterState.HIGH -> Triple(Color(0xFFEF4444), Color.White, false)
        FilterState.LOW -> Triple(Color(0xFF10B981), Color.White, false)
    }
    val label = when (state) {
        FilterState.LOW -> lowLabel
        else -> highLabel  // HIGH et OFF affichent le libelle positif
    }
    val shape = RoundedCornerShape(20.dp)
    val borderMod = if (showBorder)
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    else Modifier

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bgColor)
            .then(borderMod)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = fgColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

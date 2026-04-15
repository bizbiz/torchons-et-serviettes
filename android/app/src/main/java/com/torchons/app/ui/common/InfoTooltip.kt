package com.torchons.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Texte d'explication partage de la tolerance — affiche dans le tooltip
 * du selecteur "Tolerance au retard" sur les ecrans detail/edit/create
 * d'une tache. Reflete la nouvelle semantique v0.10.0 (refonte freshness).
 */
const val TOLERANCE_INFO_TEXT: String =
    "La tolerance ne change pas l'etat affiche d'une tache (vert / orange / rouge) " +
        "ni le moment ou elle passe en retard. Elle sert uniquement a prioriser " +
        "le selecteur \"la plus urgente\" sur la page d'accueil :\n\n" +
        "• STRICT : passe en premier des qu'elle est en retard.\n" +
        "• NORMAL : proposee si aucune tache stricte n'est en retard.\n" +
        "• SOUPLE : proposee en dernier, quand aucune autre n'est en retard.\n\n" +
        "Pour qu'une tache devienne rouge plus tot, raccourcis son intervalle " +
        "de recurrence — pas sa tolerance."

/**
 * Icone (i) cliquable qui ouvre une popup explicative.
 */
@Composable
fun InfoTooltip(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = "Info",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
            .size(20.dp)
            .padding(2.dp)
            .clickable { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Compris !")
                }
            }
        )
    }
}

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * Texte d'explication partage de la tolerance — affiche dans le tooltip
 * du selecteur "Tolerance au retard" sur les ecrans detail/edit/create
 * d'une tache. Reflete la nouvelle semantique v0.10.0 (refonte freshness).
 *
 * Composable car le texte est localise via stringResource.
 */
@Composable
fun toleranceInfoText(): String = stringResource(R.string.tolerance_info_text)

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
        contentDescription = stringResource(R.string.common_info_cd),
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
                    Text(stringResource(R.string.common_understood_excl))
                }
            }
        )
    }
}

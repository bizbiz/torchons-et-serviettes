package com.torchons.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.torchons.app.R

/**
 * Whether the backend answers right now, provided once at the root of the app (MainActivity) from
 * `BackendHealthMonitor.isOnline`. An action that cannot work offline reads it to disable its
 * button and say why ([NetworkNeededNote]) instead of failing after the tap. Defaults to true so
 * a preview or a test composition behaves as before.
 */
val LocalIsOnline = compositionLocalOf { true }

/** The "network needed" line that goes under, or next to, a button disabled while offline. */
@Composable
fun NetworkNeededNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.network_needed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

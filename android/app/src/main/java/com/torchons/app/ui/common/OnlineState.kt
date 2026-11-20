package com.torchons.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import com.torchons.app.R

/**
 * Whether the backend answers right now, provided once at the root of the app (MainActivity) from
 * `BackendHealthMonitor.isOnline`. An action that cannot work offline reads it to disable its
 * button and say why ([NetworkNeededNote]) instead of failing after the tap. Defaults to true so
 * a preview or a test composition behaves as before.
 */
val LocalIsOnline = compositionLocalOf { true }

/**
 * The "network needed" line that goes under, or next to, a button disabled while offline. It exists
 * only while offline, so it moves what is below it: fine in a short dialog, NOT on a screen or card
 * with tappable content under it (use [NetworkNeededSlot] there).
 */
@Composable
fun NetworkNeededNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.network_needed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * [NetworkNeededNote] whose line is ALWAYS laid out: shown while [visible] (offline), invisible and
 * ignored by TalkBack otherwise. Going offline then changes no height: 2026-11-20 emulator run, the
 * note appeared at the top of Home, pushed every card down ~70 px, and a tap meant for a button
 * landed on another card. Costs one blank line online; use it where content the user taps sits below.
 */
@Composable
fun NetworkNeededSlot(visible: Boolean, modifier: Modifier = Modifier) {
    val hidden = if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}
    NetworkNeededNote(modifier.then(hidden))
}

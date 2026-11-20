package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import com.torchons.app.R
import com.torchons.app.ui.common.LocalIsOnline
import com.torchons.app.ui.common.ModuleOffNoticeHost
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen

/**
 * Top of Home: greeting + sync icon + "rate a task" bubble + "?" help button, then the notice card(s)
 * telling that another member turned a household feature off (nothing when none).
 */
@Composable
fun HomeGreeting(
    displayName: String?,
    onRate: () -> Unit,
    onRateNeedsNetwork: () -> Unit,
    onSeeFoyer: () -> Unit,
) {
    val isOnline = LocalIsOnline.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = displayName?.let { stringResource(R.string.home_greeting_format, it) }
                    ?: stringResource(R.string.home_greeting_fallback),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Sync status icon (replaces the old full-width OfflineBanner
            // that used to push the whole layout down when appearing).
            com.torchons.app.ui.components.SyncStatusIcon()
            // Picking the next task to rate asks the server, so it is dimmed offline. Its reason is
            // told on tap (a toast), never as a line above the cards: that line pushed every card
            // down ~70 px the moment the network dropped, and a tap meant for a button landed on
            // another card (2026-11-20 emulator run). The button stays enabled, only to receive it.
            IconButton(onClick = { if (isOnline) onRate() else onRateNeedsNetwork() }) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = stringResource(R.string.home_rate_task_cd),
                    tint = if (isOnline) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
            HelpButton(HelpScreen.HOME, tint = MaterialTheme.colorScheme.primary)
        }
        ModuleOffNoticeHost(onSee = onSeeFoyer)
    }
}

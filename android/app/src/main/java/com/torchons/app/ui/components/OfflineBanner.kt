package com.torchons.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.core.network.ConnectionState
import com.torchons.app.ui.theme.FreshnessOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OfflineBanner(
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsState()
    val cacheTs by viewModel.cacheTimestamp.collectAsState()
    val pendingCount by viewModel.pendingOpsCount.collectAsState()
    val isOffline = state is ConnectionState.Offline
    val visible = isOffline || pendingCount > 0

    val bannerText = when {
        isOffline && pendingCount > 0 -> "Hors ligne — $pendingCount operation(s) en attente"
        isOffline && cacheTs != null -> {
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            "Hors ligne — donnees du ${fmt.format(Date(cacheTs!!))}"
        }
        isOffline -> "Hors ligne — donnees en cache"
        pendingCount > 0 -> "Synchronisation en cours..."
        else -> ""
    }

    val bgColor = if (isOffline) FreshnessOrange else MaterialTheme.colorScheme.primaryContainer
    val textColor = if (isOffline) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val icon = if (isOffline) Icons.Default.CloudOff else Icons.Default.Sync

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = bannerText,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
            )
            if (isOffline) {
                IconButton(
                    onClick = { viewModel.retry() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reessayer",
                        tint = textColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

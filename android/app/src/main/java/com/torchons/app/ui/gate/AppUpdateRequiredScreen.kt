package com.torchons.app.ui.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.core.network.VersionGateMonitor

/**
 * Terminal screen shown when the backend has refused this build (HTTP 426).
 *
 * Deliberately a dead end: no navigation, no retry, no dismiss. Anything the
 * user could tap to get past it would defeat the gate, and there is nothing
 * useful to retry -- only installing another APK changes the outcome.
 *
 * The download location is shown as TEXT rather than a link. These APKs are
 * sideloaded, so the destination is usually a file share rather than a store
 * page; showing the address lets it be read out or typed on another device even
 * when this one can no longer reach the network.
 */
@Composable
fun AppUpdateRequiredScreen(blocked: VersionGateMonitor.Blocked) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.gate_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(
                    R.string.gate_versions_format,
                    blocked.yourVersionCode,
                    blocked.minVersionCode,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            if (blocked.downloadUrl.isNotBlank()) {
                Text(
                    text = stringResource(R.string.gate_download_label),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    text = blocked.downloadUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                // A threshold raised without a download address is the way this
                // feature bricks a phone. Say so plainly rather than leave the
                // screen looking merely unhelpful.
                Text(
                    text = stringResource(R.string.gate_no_url),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

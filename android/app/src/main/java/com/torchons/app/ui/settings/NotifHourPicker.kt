package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * Hour-of-day slider of a notification (6..22). The thumb moves a local state;
 * [onCommit] fires on release only, otherwise a drag would spam the backend and
 * cancel/re-enqueue WorkManager 15+ times. `remember(hour)` re-seeds the local
 * state when the backend value arrives after the settings load.
 */
@Composable
fun NotifHourPicker(hour: Int, onCommit: (Int) -> Unit) {
    var pendingHour by remember(hour) { mutableStateOf(hour) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_notif_hour_format, pendingHour),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
    Slider(
        value = pendingHour.toFloat(),
        onValueChange = { pendingHour = it.toInt() },
        onValueChangeFinished = { if (pendingHour != hour) onCommit(pendingHour) },
        valueRange = 6f..22f,
        steps = 15,
        modifier = Modifier.fillMaxWidth(),
    )
}

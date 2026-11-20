package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.LocalIsOnline

/**
 * Banner "unconfirmed presence days" of the home screen (home freeze v2). « Résoudre » reopens the
 * presence prompt, which the server hands out: disabled offline, with the reason under the text
 * (D7, 2026-11-20 audit: the tap used to do nothing).
 */
@Composable
internal fun HomePresenceBanner(daysCount: Int, onResolve: () -> Unit) {
    val isOnline = LocalIsOnline.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.home_unresolved_presence_days, daysCount, daysCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF92400E),
                )
                val subtitle =
                    if (isOnline) R.string.home_unresolved_presence_subtitle else R.string.network_needed
                Text(
                    stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309),
                )
            }
            OutlinedButton(
                onClick = onResolve,
                enabled = isOnline,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF92400E)),
            ) {
                Text(
                    stringResource(R.string.home_unresolved_presence_resolve),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

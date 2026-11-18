package com.torchons.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip

/**
 * A personal option attached to a COLLECTIVE module that the current household has
 * turned off. The option stays visible but disabled (its value is kept: it still
 * applies to the user's other households) and this says why.
 */
data class ModuleOffInfo(
    val foyerName: String,
    val moduleTitle: String,
    val onOpenFoyerSettings: () -> Unit,
)

@Composable
fun ModuleOffNote(info: ModuleOffInfo) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_module_off_format, info.foyerName, info.moduleTitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = info.onOpenFoyerSettings) {
            Text(stringResource(R.string.settings_module_off_link))
        }
    }
}

/**
 * Personal choice about the household's `motivation` module: show points and streaks
 * for me. Checked = shown (a "Hide" switch under a "Gamification" title read as "on =
 * active" and hid points by surprise). The module itself is a household decision.
 */
@Composable
fun ShowPointsCard(
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    moduleOff: ModuleOffInfo?,
    helpLinkVisible: Boolean,
    onWhyNoPoints: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎯", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_gamif_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_gamif_switch_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_gamif_switch_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = shown, enabled = moduleOff == null, onCheckedChange = onShownChange)
            }
            moduleOff?.let { ModuleOffNote(it) }
            if (helpLinkVisible) WhyNoPointsLink(onWhyNoPoints)
        }
    }
}

@Composable
private fun WhyNoPointsLink(onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_gamif_why_no_points),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Personal choice about the household's `presence` module: hide on-site tasks from MY
 * list when I am not on the home WiFi. The WiFi list itself stays on the Foyer screen.
 */
@Composable
fun HomeWifiCard(
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,
    moduleOff: ModuleOffInfo?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.foyer_presence_hide_off_wifi_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                InfoTooltip(
                    title = stringResource(R.string.foyer_presence_hide_off_wifi_tooltip_title),
                    description = stringResource(R.string.foyer_presence_hide_off_wifi_tooltip_description),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                title = stringResource(R.string.foyer_presence_hide_off_wifi_switch_title),
                subtitle = stringResource(R.string.foyer_presence_hide_off_wifi_switch_subtitle),
                checked = hidden,
                onCheckedChange = onHiddenChange,
                enabled = moduleOff == null,
            )
            moduleOff?.let { ModuleOffNote(it) }
        }
    }
}

/** Personal choice about the household's `repartition` module: the affinity popup. */
@Composable
fun AffinityPopupCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    moduleOff: ModuleOffInfo?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QuestionMark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_affinity_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                title = stringResource(R.string.settings_affinity_switch_label),
                subtitle = stringResource(R.string.settings_affinity_switch_subtitle),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = moduleOff == null,
            )
            moduleOff?.let { ModuleOffNote(it) }
        }
    }
}

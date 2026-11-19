package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip

/*
 * Household settings cards that belong to the `repartition` module (affinity and
 * effort renewal, fair-share tolerance). The caller shows them only when the module
 * is on; they are moved out of FoyerSettingsScreen, which is over its size budget.
 */

/** Months after which affinity and effort ratings are asked again (1 to 12). */
@Composable
internal fun AffinityRenewalCard(months: Int, onChange: (Int) -> Unit) {
    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.foyer_affinity_title_format, months),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            InfoTooltip(
                title = stringResource(R.string.foyer_affinity_tooltip_title),
                description = stringResource(R.string.foyer_affinity_tooltip_description),
            )
        }
        Slider(
            value = months.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 1f..12f,
            steps = 10,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Gap, in percent, tolerated between a member's share and their target share. */
@Composable
internal fun FairShareToleranceCard(percent: Int, onChange: (Int) -> Unit) {
    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Balance,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.foyer_fair_share_title_format, percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            InfoTooltip(
                title = stringResource(R.string.foyer_fair_share_tooltip_title),
                description = stringResource(R.string.foyer_fair_share_tooltip_description),
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

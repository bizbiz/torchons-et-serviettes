package com.torchons.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip

/**
 * Household settings card for the affinity and effort renewal. These ratings are
 * core (no module owns them), so the household screen always shows it.
 * Moved out of FoyerSettingsScreen, which is over its size budget.
 *
 * Months after which affinity and effort ratings are asked again (1 to 24).
 */
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
            valueRange = 1f..24f,
            steps = 22,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

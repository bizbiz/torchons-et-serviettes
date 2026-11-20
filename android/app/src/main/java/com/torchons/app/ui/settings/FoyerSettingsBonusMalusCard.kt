package com.torchons.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.InfoTooltip
import com.torchons.app.ui.common.LocalIsOnline
import com.torchons.app.ui.common.NetworkNeededSlot

// Collapsible "Bonus / Malus" card (the 13 thresholds) of the household settings screen.
@Composable
internal fun FoyerBonusMalusCard(viewModel: SettingsViewModel) {
    // (9) Bonus / Malus de points (replie par defaut, complexe)
    val bonusConfig by viewModel.bonusConfig.collectAsState()
    var showBonusMalus by remember { mutableStateOf(false) }
    SettingCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showBonusMalus = !showBonusMalus },
        ) {
            Text(
                text = stringResource(R.string.foyer_bonus_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            InfoTooltip(
                title = stringResource(R.string.foyer_bonus_section_tooltip_title),
                description = stringResource(R.string.foyer_bonus_section_tooltip_description),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (showBonusMalus)
                    Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }

        // A refused change rebuilds the sliders from the server's values (key), they must not keep the drag.
        val resets by viewModel.bonusResets.collectAsState()
        if (showBonusMalus && bonusConfig != null) key(resets) {
            val cfg = bonusConfig!!
            Spacer(Modifier.height(8.dp))
            NetworkNeededSlot(visible = !LocalIsOnline.current)

            Text(
                stringResource(R.string.foyer_bonus_group_late),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            BonusSlider(
                stringResource(R.string.foyer_bonus_pct_label),
                stringResource(R.string.foyer_bonus_pct_value_format, (cfg.bonusPct * 100).toInt()),
                cfg.bonusPct, 0f..2f, 19,
                stringResource(R.string.foyer_bonus_pct_desc),
            ) { viewModel.updateBonusConfig("bonus_pct", it) }
            BonusSlider(
                stringResource(R.string.foyer_bonus_threshold_strict),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusStrict * 100).toInt()),
                cfg.seuilBonusStrict, 0f..1f, 19,
                stringResource(R.string.foyer_bonus_threshold_strict_desc),
            ) { viewModel.updateBonusConfig("seuil_bonus_strict", it) }
            BonusSlider(
                stringResource(R.string.foyer_bonus_threshold_normal),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusNormal * 100).toInt()),
                cfg.seuilBonusNormal, 0f..1f, 19,
                stringResource(R.string.foyer_bonus_threshold_normal_desc),
            ) { viewModel.updateBonusConfig("seuil_bonus_normal", it) }
            BonusSlider(
                stringResource(R.string.foyer_bonus_threshold_souple),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilBonusSouple * 100).toInt()),
                cfg.seuilBonusSouple, 0f..1f, 19,
                stringResource(R.string.foyer_bonus_threshold_souple_desc),
            ) { viewModel.updateBonusConfig("seuil_bonus_souple", it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                stringResource(R.string.foyer_malus_group),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            BonusSlider(
                stringResource(R.string.foyer_malus_floor_label),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.plancherMalus * 100).toInt()),
                cfg.plancherMalus, 0.1f..1f, 17,
                stringResource(R.string.foyer_malus_floor_desc),
            ) { viewModel.updateBonusConfig("plancher_malus", it) }
            BonusSlider(
                stringResource(R.string.foyer_malus_threshold_strict),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusStrict * 100).toInt()),
                cfg.seuilMalusStrict, 0f..1f, 19,
                stringResource(R.string.foyer_malus_threshold_strict_desc),
            ) { viewModel.updateBonusConfig("seuil_malus_strict", it) }
            BonusSlider(
                stringResource(R.string.foyer_malus_threshold_normal),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusNormal * 100).toInt()),
                cfg.seuilMalusNormal, 0f..1f, 19,
                stringResource(R.string.foyer_malus_threshold_normal_desc),
            ) { viewModel.updateBonusConfig("seuil_malus_normal", it) }
            BonusSlider(
                stringResource(R.string.foyer_malus_threshold_souple),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.seuilMalusSouple * 100).toInt()),
                cfg.seuilMalusSouple, 0f..1f, 19,
                stringResource(R.string.foyer_malus_threshold_souple_desc),
            ) { viewModel.updateBonusConfig("seuil_malus_souple", it) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                stringResource(R.string.foyer_bump_group),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            BonusSlider(
                stringResource(R.string.foyer_bump_window_min),
                stringResource(R.string.foyer_bump_window_value_days_format, cfg.bumpYMinDays),
                cfg.bumpYMinDays.toFloat(), 30f..365f, 10,
                stringResource(R.string.foyer_bump_window_min_desc),
            ) { viewModel.updateBonusConfig("bump_y_min_days", it.toInt()) }
            BonusSlider(
                stringResource(R.string.foyer_bump_window_max),
                stringResource(R.string.foyer_bump_window_value_days_format, cfg.bumpYMaxDays),
                cfg.bumpYMaxDays.toFloat(), 180f..3650f, 10,
                stringResource(R.string.foyer_bump_window_max_desc),
            ) { viewModel.updateBonusConfig("bump_y_max_days", it.toInt()) }
            BonusSlider(
                stringResource(R.string.foyer_bump_min_completions),
                stringResource(R.string.foyer_bump_value_int_format, cfg.bumpZMin),
                cfg.bumpZMin.toFloat(), 1f..10f, 8,
                stringResource(R.string.foyer_bump_min_completions_desc),
            ) { viewModel.updateBonusConfig("bump_z_min", it.toInt()) }
            BonusSlider(
                stringResource(R.string.foyer_bump_ratio),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.bumpXRatio * 100).toInt()),
                cfg.bumpXRatio, 0.3f..1f, 13,
                stringResource(R.string.foyer_bump_ratio_desc),
            ) { viewModel.updateBonusConfig("bump_x_ratio", it) }
            BonusSlider(
                stringResource(R.string.foyer_bump_grace),
                stringResource(R.string.foyer_bump_value_pct_format, (cfg.bumpGraceRatio * 100).toInt()),
                cfg.bumpGraceRatio, 0.1f..2f, 18,
                stringResource(R.string.foyer_bump_grace_desc),
            ) { viewModel.updateBonusConfig("bump_grace_ratio", it) }
        }
    }
}

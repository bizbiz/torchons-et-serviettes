package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torchons.app.util.DurationUnit
import com.torchons.app.util.FrequencyScale

/**
 * Days / Weeks / Months toggle above a 1-to-15 slider counted in that unit, shared by the
 * re-vote ([FrequencyVoteDialog]) and the rating ([RatingDialog]). See [FrequencyScale].
 *
 * The caller owns [days]; this composable only keeps the unit. Opening on a value the slider
 * cannot hold exactly (20 days shows on 3 weeks) does not change [days] until the user acts.
 */
@Composable
fun FrequencyScaleSlider(
    days: Int,
    onDaysChange: (Int) -> Unit,
    resetKey: Any?,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit = {},
) {
    var unit by remember(resetKey) { mutableStateOf(FrequencyScale.initialUnit(days)) }
    val maxCount = FrequencyScale.maxCount(unit)
    val count = FrequencyScale.countFor(unit, days)

    Column(modifier = modifier.fillMaxWidth()) {
        val units = DurationUnit.values().toList()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            units.forEachIndexed { idx, u ->
                SegmentedButton(
                    selected = unit == u,
                    onClick = {
                        if (u != unit) {
                            onDaysChange(FrequencyScale.daysFor(u, FrequencyScale.countFor(u, days)))
                            unit = u
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(idx, units.size),
                ) { Text(stringResource(u.labelRes)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        label()
        Slider(
            value = count.toFloat(),
            onValueChange = { v ->
                onDaysChange(FrequencyScale.daysFor(unit, Math.round(v).coerceIn(1, maxCount)))
            },
            valueRange = 1f..maxCount.toFloat(),
            steps = (maxCount - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = MaterialTheme.typography.labelSmall)
            Text("$maxCount", style = MaterialTheme.typography.labelSmall)
        }
    }
}

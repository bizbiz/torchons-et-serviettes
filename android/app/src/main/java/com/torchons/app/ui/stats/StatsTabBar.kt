package com.torchons.app.ui.stats

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.torchons.app.ui.help.HelpButton
import com.torchons.app.ui.help.HelpScreen

/** The Stats tabs, with the screen's "?" help button at the end of the row. */
@Composable
internal fun StatsTabBar(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = selectedIndex, modifier = Modifier.weight(1f)) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
        HelpButton(HelpScreen.STATS)
    }
}

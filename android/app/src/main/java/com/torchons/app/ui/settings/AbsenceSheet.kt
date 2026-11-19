package com.torchons.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.torchons.app.R
import com.torchons.app.data.model.Task
import com.torchons.app.util.AbsenceFreeze
import com.torchons.app.util.AbsenceFreeze.Advice

/**
 * « Pendant une absence »: which tasks pause during an absence and which keep going, with
 * one tip per task to move it to the other side. Opened from the household presence card
 * and from the top of the Contexts screen.
 *
 * [freezeEnabled] is the caller's value when it has one; otherwise the sheet reads it itself.
 * Unknown (offline, no answer yet) counts as enabled, the usual case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbsenceSheet(
    onDismiss: () -> Unit,
    freezeEnabled: Boolean? = null,
    viewModel: AbsenceSheetViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState()
    val fetched by viewModel.freezeEnabled.collectAsState()
    val freeze = freezeEnabled ?: fetched ?: true
    val entries = remember(tasks, freeze) {
        AbsenceFreeze.classify(tasks, { it.contexts }, freeze)
    }
    val (paused, running) = entries.partition { it.frozen }
    var tab by rememberSaveable { mutableStateOf(0) }
    val shown = if (tab == 0) paused else running

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.absence_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (!freeze) FreezeOffBanner()
            Spacer(Modifier.height(8.dp))
            AbsenceTabs(tab, paused.size, running.size) { tab = it }
            Spacer(Modifier.height(12.dp))
            val sentence = if (tab == 0) R.string.absence_tab_paused_sentence
            else R.string.absence_tab_running_sentence
            Text(
                text = stringResource(sentence),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.absence_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.item.id }) { entry -> AbsenceTaskRow(entry) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Text button « Voir les tâches en pause » that owns and opens the sheet. */
@Composable
fun AbsenceSheetLink(freezeEnabled: Boolean? = null, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text(stringResource(R.string.absence_sheet_link))
    }
    if (open) AbsenceSheet(onDismiss = { open = false }, freezeEnabled = freezeEnabled)
}

@Composable
private fun FreezeOffBanner() {
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.absence_freeze_off_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbsenceTabs(
    selected: Int,
    pausedCount: Int,
    runningCount: Int,
    onSelect: (Int) -> Unit,
) {
    SecondaryTabRow(selectedTabIndex = selected) {
        Tab(
            selected = selected == 0,
            onClick = { onSelect(0) },
            text = { Text(stringResource(R.string.absence_tab_paused, pausedCount)) },
        )
        Tab(
            selected = selected == 1,
            onClick = { onSelect(1) },
            text = { Text(stringResource(R.string.absence_tab_running, runningCount)) },
        )
    }
}

@Composable
private fun AbsenceTaskRow(entry: AbsenceFreeze.Entry<Task>) {
    var expanded by rememberSaveable(entry.item.id) { mutableStateOf(false) }
    val advice = entry.advice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = advice != null) { expanded = !expanded }
            .padding(vertical = 8.dp),
    ) {
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.item.title, style = MaterialTheme.typography.bodyLarge)
                if (entry.contexts.isNotEmpty()) {
                    Text(
                        text = entry.contexts.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (advice != null) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded && advice != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = adviceText(advice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}

/** The tip's sentence. The two setting names come from the Contexts screen's own labels. */
@Composable
private fun adviceText(advice: Advice): String {
    val res = LocalContext.current.resources
    val presenceLabel = stringResource(R.string.context_requires_presence_label)
    val accrueLabel = stringResource(R.string.context_accrues_when_away_label)
    return when (advice) {
        is Advice.KeepGoing -> res.getQuantityString(
            R.plurals.absence_advice_keep_going,
            advice.presenceContexts.size,
            accrueLabel,
            advice.presenceContexts.joinToString(", "),
        )
        is Advice.Pause -> {
            val base = res.getQuantityString(
                R.plurals.absence_advice_pause,
                advice.accrueContexts.size,
                accrueLabel,
                advice.accrueContexts.joinToString(", "),
            )
            if (advice.alsoNeedsPresence) {
                base + " " + stringResource(R.string.absence_advice_also_presence, presenceLabel)
            } else {
                base
            }
        }
        Advice.AddPresence -> stringResource(R.string.absence_advice_add_presence, presenceLabel)
    }
}

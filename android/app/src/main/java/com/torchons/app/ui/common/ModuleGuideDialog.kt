package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R

/**
 * Guide page of a module: pitch, then three headed sections (what it is for,
 * why, how to use it well) and the example. A blank section is not rendered.
 * Dismissing (button or outside tap) calls [onDismiss].
 */
@Composable
fun ModuleGuideDialog(
    guide: ModuleGuide,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = guide.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (guide.pitch.isNotBlank()) {
                    Text(text = guide.pitch, style = MaterialTheme.typography.bodyMedium)
                }
                ModuleGuideSections(guide = guide, includePurpose = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.module_guide_understood))
            }
        },
    )
}

/**
 * App-open guide host: shows the due guide (if any) unless a prompt modal is
 * on screen, and records it as seen on dismiss. Drop it anywhere in the home
 * screen; it renders nothing otherwise.
 */
@Composable
fun ModuleGuideHost(promptShowing: Boolean) {
    val vm: ModuleGuideViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val due by vm.dueGuide.collectAsState()
    val guide = due
    if (guide != null && !promptShowing) {
        ModuleGuideDialog(guide = guide, onDismiss = vm::dismissDueGuide)
    }
}

/**
 * The headed sections, shared with the DISCOVER_MODULE prompt. The prompt
 * passes `includePurpose = false` because it already shows the details as its
 * own body text.
 */
@Composable
fun ModuleGuideSections(guide: ModuleGuide, includePurpose: Boolean) {
    if (includePurpose) GuideSection(R.string.module_guide_section_purpose, guide.details)
    GuideSection(R.string.module_guide_section_why, guide.why)
    GuideSection(R.string.module_guide_section_how, guide.how)
    guide.example?.takeIf { it.isNotBlank() }?.let { example ->
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = example,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GuideSection(titleRes: Int, body: String) {
    if (body.isBlank()) return
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(text = body, style = MaterialTheme.typography.bodySmall)
}

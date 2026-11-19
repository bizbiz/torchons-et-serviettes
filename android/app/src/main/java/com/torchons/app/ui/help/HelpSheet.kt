package com.torchons.app.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.common.Modules
import com.torchons.app.ui.common.gamificationVisible
import com.torchons.app.ui.common.moduleEnabled

/*
 * The "?" of a screen: an icon button that opens a bottom sheet explaining that screen (title =
 * the screen's name, then sections of title + text). Nothing automatic: the sheet only opens on a
 * tap, and nothing remembers that it was seen. Content: HelpContent.kt.
 */

/** The "?" button. [tint] is the icon colour; the default follows the surrounding content colour. */
@Composable
internal fun HelpButton(screen: HelpScreen, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.help_button_cd),
            tint = tint,
        )
    }
    if (open) HelpSheet(screen, onDismiss = { open = false })
}

/**
 * The sections of [screen] that show now. Every module state is read for each section, in a fixed
 * order, so the composable calls stay stable whatever the household's modules are.
 */
@Composable
private fun visibleSections(screen: HelpScreen): List<HelpSection> {
    val keys = screen.sections.mapNotNull { (it.condition as? HelpCondition.Module)?.key }.distinct()
    val modules = keys.associateWith { moduleEnabled(it) }
    val motivationOn = moduleEnabled(Modules.MOTIVATION)
    val pointsShown = gamificationVisible()
    return screen.sections.filter {
        when (val c = it.condition) {
            HelpCondition.Always -> true
            is HelpCondition.Module -> modules[c.key] == true
            HelpCondition.Gamification -> pointsShown
            HelpCondition.MotivationModule -> motivationOn
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(screen: HelpScreen, onDismiss: () -> Unit) {
    val sections = visibleSections(screen)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(screen.title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(section.title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(section.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

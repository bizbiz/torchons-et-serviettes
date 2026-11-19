package com.torchons.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.ui.navigation.Routes
import com.torchons.app.ui.navigation.bottomNavItems

/*
 * Miniatures of the welcome screen: the real path drawn with the real labels (nav_*, the
 * household card, « M'écrire ») instead of a sentence describing it. Design validated by
 * Quentin on 2026-11-19 (todo « Écran de bienvenue plus illustré »). Not clickable: one
 * contentDescription per miniature for TalkBack.
 */

/** Settings tab, the household card highlighted. */
@Composable
internal fun MiniFoyerCard(foyerName: String) {
    MiniFrame(stringResource(R.string.first_run_welcome_mini_foyer_cd)) {
        Pointed {
            Highlight {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        stringResource(R.string.settings_foyer_card_title_format, foyerName),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.settings_foyer_card_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MiniNavBar(selected = Routes.SETTINGS)
    }
}

/** A screen header, its « ? » highlighted. */
@Composable
internal fun MiniHelpButton() {
    MiniFrame(stringResource(R.string.first_run_welcome_mini_help_cd)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.nav_home),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Pointed {
                Highlight {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

/** Bottom of Settings: « Un mot de Quentin » and its « M'écrire » button highlighted. */
@Composable
internal fun MiniWriteToMe() {
    MiniFrame(stringResource(R.string.first_run_welcome_mini_write_cd)) {
        Text(
            stringResource(R.string.first_run_welcome_bottom_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.support_dev_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Pointed {
            Highlight {
                Text(
                    stringResource(R.string.support_dev_email_button),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MiniNavBar(selected = Routes.SETTINGS)
    }
}

/** Dashed rounded frame: reads as « a piece of a screen », not as a button. */
@Composable
private fun MiniFrame(description: String, content: @Composable () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                )
            }
            .padding(12.dp),
    ) { content() }
}

/** The element to find, outlined in the primary color. */
@Composable
private fun Highlight(content: @Composable () -> Unit) {
    Box(
        Modifier
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
    ) { content() }
}

/** [content] followed by an arrow pointing at it. */
@Composable
private fun Pointed(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        content()
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The bottom bar, same items and icons as NavGraph, [selected] tab marked. */
@Composable
private fun MiniNavBar(selected: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        bottomNavItems.forEach { item ->
            val on = item.route == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .then(
                            if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) { Icon(item.icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                Text(
                    stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

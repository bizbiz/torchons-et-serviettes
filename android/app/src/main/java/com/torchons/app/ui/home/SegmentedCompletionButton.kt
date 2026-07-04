package com.torchons.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Full-width segmented action button used by the home SuggestionCard:
 * [left slot | center action | (chevron slot) | right slot], with slots
 * visually delimited by thin vertical dividers.
 *
 * The center tap is the primary action (immediate completion). Side slots
 * navigate the shared selected day or open the date picker. The optional
 * chevron slot anchors a participant-selection dropdown: pass the
 * [androidx.compose.material3.DropdownMenu] through [chevronMenu] so it
 * anchors to the chevron zone.
 */
@Composable
fun SegmentedCompletionButton(
    centerLabel: String,
    leftIcon: ImageVector,
    leftContentDescription: String,
    onLeftClick: () -> Unit,
    rightIcon: ImageVector,
    rightContentDescription: String,
    onRightClick: () -> Unit,
    onCenterClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    chevronContentDescription: String? = null,
    onChevronClick: (() -> Unit)? = null,
    chevronMenu: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val contentColor =
        if (filled) Color.White else MaterialTheme.colorScheme.primary
    Surface(
        shape = shape,
        color = if (filled) com.torchons.app.util.TaskUrgencyCalculator.VERT else Color.Transparent,
        contentColor = contentColor,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotBox(
                width = 48.dp,
                icon = leftIcon,
                contentDescription = leftContentDescription,
                onClick = onLeftClick,
            )
            SlotDivider(contentColor)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClick = onCenterClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (onChevronClick != null) {
                SlotDivider(contentColor)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(40.dp)
                        .clickable(role = Role.Button, onClick = onChevronClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = chevronContentDescription,
                        modifier = Modifier.size(24.dp),
                    )
                    // Anchor for the participants DropdownMenu.
                    chevronMenu?.invoke()
                }
            }
            SlotDivider(contentColor)
            SlotBox(
                width = 48.dp,
                icon = rightIcon,
                contentDescription = rightContentDescription,
                onClick = onRightClick,
            )
        }
    }
}

@Composable
private fun SlotBox(
    width: androidx.compose.ui.unit.Dp,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SlotDivider(contentColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 10.dp)
            .width(1.dp)
            .background(contentColor.copy(alpha = 0.35f)),
    )
}

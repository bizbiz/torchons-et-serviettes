package com.torchons.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.util.TaskUrgencyCalculator

/**
 * What the home shows when it has no suggestion to display. Three cases, kept apart because
 * they mean different things:
 * - [backendUnreachable]: the server never answered and the cache is empty — nothing is known;
 * - [householdEmpty]: the household has no active task yet — an invitation, not a success
 *   (until 2026-11-15 it read « Tout est frais », the void presented as a success);
 * - otherwise: tasks exist and none is due — « Tout est frais ».
 */
@Composable
internal fun HomeEmptyState(
    backendUnreachable: Boolean,
    householdEmpty: Boolean,
    onAddTasks: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                backendUnreachable -> EmptyStateText(
                    icon = Icons.Default.Warning,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = stringResource(R.string.home_backend_unreachable_title),
                    message = stringResource(R.string.home_backend_unreachable_message),
                )
                householdEmpty -> {
                    EmptyStateText(
                        icon = Icons.AutoMirrored.Filled.List,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = stringResource(R.string.home_no_tasks_title),
                        message = stringResource(R.string.home_no_tasks_message),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddTasks) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.home_no_tasks_button))
                    }
                }
                else -> EmptyStateText(
                    icon = Icons.Default.Check,
                    tint = TaskUrgencyCalculator.VERT,
                    title = stringResource(R.string.home_all_fresh_title),
                    message = stringResource(R.string.home_all_fresh_message),
                )
            }
        }
    }
}

@Composable
private fun EmptyStateText(icon: ImageVector, tint: Color, title: String, message: String) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = tint,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

package com.torchons.app.ui.tasklist

import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torchons.app.R

/**
 * Cas foyer 2-pers + Fait ensemble : DatePickerDialog direct, pas de
 * picker participants (split auto entre les deux membres).
 * Moved out of TaskListScreen on 2026-10-18, behaviour unchanged.
 *
 * @param onConfirm receives the picked date as `YYYY-MM-DDT12:00:00Z`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwoPersonTogetherDatePicker(
    onConfirm: (isoDate: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val millis = datePickerState.selectedDateMillis
                if (millis != null) {
                    val date = java.time.Instant.ofEpochMilli(millis)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                    onConfirm("${date}T12:00:00Z")
                }
                onDismiss()
            }) { Text(stringResource(R.string.common_validate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) { DatePicker(state = datePickerState) }
}

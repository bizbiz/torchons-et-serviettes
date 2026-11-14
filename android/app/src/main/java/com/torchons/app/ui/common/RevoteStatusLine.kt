package com.torchons.app.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The status line of a re-vote request, shown under the row of [userId] when the notice is theirs. */
@Composable
fun RevoteStatusLine(notice: RevoteNotice?, userId: String) {
    if (notice == null || notice.userId != userId) return
    val res = LocalContext.current.resources
    Text(
        text = revoteNoticeText(res, notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

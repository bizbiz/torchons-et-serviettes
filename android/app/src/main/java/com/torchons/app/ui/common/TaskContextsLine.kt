package com.torchons.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef

/** What the vote dialogs show of the task: its title, and its [contextsLine] under it. */
data class VoteTaskHeader(val title: String, val contextsLine: String? = null)

fun voteHeader(task: Task): VoteTaskHeader = VoteTaskHeader(task.title, contextsLine(task.contexts))

/**
 * Every context of a task on one line, « 🛋 Salon · 🧽 Ménage », main context first; null when
 * the task has none. Two tasks may share a title (« Passer l'aspirateur » in the living room and
 * in the bathroom): the vote dialogs show this line under the title so a re-vote request says
 * which one is meant (Quentin, 2026-11-11).
 */
fun contextsLine(contexts: List<TaskContextRef>): String? =
    contexts.sortedBy { it.position }.joinToString(" · ") { it.label }.ifEmpty { null }

/** The [contextsLine] under a dialog's task title, in the style of RatingDialog's room label. */
@Composable
fun TaskContextsLine(line: String?) {
    if (line == null) return
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

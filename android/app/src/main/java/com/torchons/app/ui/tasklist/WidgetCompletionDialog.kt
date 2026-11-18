package com.torchons.app.ui.tasklist

import android.content.Context
import com.torchons.app.data.model.Task
import com.torchons.app.widget.TaskWidgetEntryPoint
import com.torchons.app.widget.WidgetLaunch
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/** State of the « Date + Qui » dialog of the task list (ui/common/CompletionDialog). */
internal data class TaskListCompletionDialogState(
    val taskId: String,
    val taskTitle: String,
    val assignedIds: Set<String>,
)

/**
 * The task a widget button names, read from the whole local cache. The Tasks tab's list is
 * FILTERED (« À faire » by default) while the widget lists every task: a task the filters hide
 * was not found there, and « Déjà fait » opened without its title or its assignees (seen on the
 * emulator 2026-11-11, and the reading of Quentin's « Jamais de nom » on v0.44.1).
 */
internal suspend fun findWidgetTask(context: Context, taskId: String): Task? =
    EntryPointAccessors.fromApplication(context.applicationContext, TaskWidgetEntryPoint::class.java)
        .taskRepository().observeTaskById(taskId).first()

/** The « Date + Qui » dialog a widget request opens: the same as the card's. */
internal fun widgetCompletionDialog(req: WidgetLaunch.Request, task: Task?): TaskListCompletionDialogState =
    TaskListCompletionDialogState(
        taskId = req.taskId,
        taskTitle = task?.title.orEmpty(),
        assignedIds = task?.assignedToIds?.toSet() ?: emptySet(),
    )

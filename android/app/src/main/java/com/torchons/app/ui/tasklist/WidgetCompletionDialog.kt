package com.torchons.app.ui.tasklist

import android.content.Context
import com.torchons.app.data.model.Task
import com.torchons.app.ui.common.CompletionDialogMode
import com.torchons.app.widget.TaskWidgetEntryPoint
import com.torchons.app.widget.WidgetLaunch
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/** Etat du dialog partage "Deja fait" / "Fait ensemble" sur la tasklist. */
internal data class TaskListCompletionDialogState(
    val mode: CompletionDialogMode,
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

/** « Ensemble » asked from the widget, unless the task is solo (one assignee): done alone, never together. */
internal fun WidgetLaunch.Request.togetherFor(task: Task?): Boolean = together && task?.assignedToIds?.size != 1

/** The dialog a widget request opens, « Déjà fait » (Single) or « Fait ensemble » (Multi). */
internal fun widgetCompletionDialog(req: WidgetLaunch.Request, task: Task?): TaskListCompletionDialogState =
    TaskListCompletionDialogState(
        mode = if (req.togetherFor(task)) CompletionDialogMode.Multi else CompletionDialogMode.Single,
        taskId = req.taskId,
        taskTitle = task?.title.orEmpty(),
        assignedIds = task?.assignedToIds?.toSet() ?: emptySet(),
    )

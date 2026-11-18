package com.torchons.app.ui.tasklist

/*
 * The Tasks tab without its six tri-state chips (2026-11-18): only what is to do shows by default
 * (isToDo), in two blocks, and the tasks up to date wait behind a button. The widget lists the
 * same two blocks, without the button. Pure functions: TaskListBlocksTest.
 */

/** The tab's blocks, each in sortForTaskList order. */
data class TaskListBlocks(
    /** Tasks with a cadence that are due: red, then orange. */
    val toDo: List<TaskWithUrgency> = emptyList(),
    /** Tasks without cadence that isToDo keeps: never done first, then « uncertain » ones done. */
    val withoutCadence: List<TaskWithUrgency> = emptyList(),
    /** What isToDo leaves out: green tasks with a cadence, then one-offs already done. */
    val upToDate: List<TaskWithUrgency> = emptyList(),
) {
    val toDoCount: Int get() = toDo.size + withoutCadence.size
}

internal fun taskListBlocks(tasks: List<TaskWithUrgency>): TaskListBlocks {
    val (due, upToDate) = sortForTaskList(tasks).partition(::isToDo)
    val (withCadence, without) = due.partition { it.group == RecurrenceGroup.CADENCE }
    return TaskListBlocks(withCadence, without, upToDate)
}

/** One row of the tab's list. */
internal sealed interface TaskListRow {
    data class Item(val task: TaskWithUrgency) : TaskListRow
    data class Header(val kind: HeaderKind) : TaskListRow
    /** « Afficher les tâches à jour (N) » / « Masquer les tâches à jour ». */
    data class UpToDateToggle(val count: Int, val expanded: Boolean) : TaskListRow
}

internal enum class HeaderKind { WITHOUT_CADENCE, UP_TO_DATE }

/**
 * The rows, top to bottom. A header only above a non-empty block. While searching every match
 * shows, up to date included, and there is no toggle; otherwise the up-to-date block shows only
 * when [showUpToDate], and the toggle closes the list whenever that block is not empty.
 */
internal fun taskListRows(blocks: TaskListBlocks, showUpToDate: Boolean, searching: Boolean): List<TaskListRow> {
    val rows = mutableListOf<TaskListRow>()
    blocks.toDo.mapTo(rows) { TaskListRow.Item(it) }
    if (blocks.withoutCadence.isNotEmpty()) {
        rows += TaskListRow.Header(HeaderKind.WITHOUT_CADENCE)
        blocks.withoutCadence.mapTo(rows) { TaskListRow.Item(it) }
    }
    if (blocks.upToDate.isEmpty()) return rows
    if (searching || showUpToDate) {
        rows += TaskListRow.Header(HeaderKind.UP_TO_DATE)
        blocks.upToDate.mapTo(rows) { TaskListRow.Item(it) }
    }
    if (!searching) rows += TaskListRow.UpToDateToggle(blocks.upToDate.size, showUpToDate)
    return rows
}

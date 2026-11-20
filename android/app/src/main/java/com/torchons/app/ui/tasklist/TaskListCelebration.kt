package com.torchons.app.ui.tasklist

/**
 * The completion is written at tap time (2026-11-19: written after the celebration, it was lost
 * when the user left within 3 s), so the cache turns the task green at once and « À faire » would
 * drop its card in the middle of the animation. While a task celebrates, the list keeps showing it
 * as it was at the tap: [held] maps the task id to that earlier [TaskWithUrgency]. Pure, shared by
 * the collector and the tests (TaskListCelebrationTest).
 */
internal fun holdCelebrated(result: FilteredResult, held: Map<String, TaskWithUrgency>): FilteredResult {
    if (held.isEmpty()) return result
    val tasks = sortForTaskList(result.tasks.map { held[it.task.id] ?: it })
    return result.copy(tasks = tasks, blocks = taskListBlocks(tasks))
}

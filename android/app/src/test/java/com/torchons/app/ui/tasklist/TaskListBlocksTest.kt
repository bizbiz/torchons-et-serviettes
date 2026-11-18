package com.torchons.app.ui.tasklist

import com.torchons.app.data.model.Task
import com.torchons.app.util.TaskUrgencyCalculator.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Tasks tab without its six tri-state chips (2026-11-18): blocks and rows (TaskListBlocks.kt).
 *
 * PATHS by which the tab could show the wrong tasks, the wrong count or the wrong order:
 *   (1) the to-do block: cadence red then orange, nothing green ............................ COVERED
 *   (2) the « Tâches sans fréquence » block: never-done one-offs and « uncertain » tasks .... COVERED
 *   (3) N, the up-to-date count: green cadence tasks and one-offs already done ............. COVERED
 *   (4) folded: no up-to-date rows, the toggle last, absent when N = 0 ..................... COVERED
 *   (5) unfolded: header then up-to-date rows, then the « hide » toggle .................... COVERED
 *   (6) searching: everything with both headers, no toggle .................................. COVERED
 *   (7) a header only above a non-empty block ................................................ COVERED
 *   non couvert : the context filter and the search matching (applyFilters): they compute the
 *                 urgency with Resources, Android only; this file starts from their output.
 */
class TaskListBlocksTest {

    private fun item(
        id: String,
        state: State = State.GREEN,
        days: Int = 0,
        group: RecurrenceGroup = RecurrenceGroup.CADENCE,
        lastDone: String? = null,
        uncertain: Boolean = false,
    ) = TaskWithUrgency(
        task = Task(id = id, title = id),
        state = state,
        daysUntilDue = days,
        countdownLabel = "",
        categoryName = null,
        categoryColor = null,
        roomName = null,
        roomEmoji = null,
        group = group,
        lastCompletedAt = lastDone,
        isUncertain = uncertain,
    )

    private val tasks = listOf(
        item("green", State.GREEN, days = 4),
        item("one-off-done", group = RecurrenceGroup.SANS_CADENCE, lastDone = "2026-11-01T10:00:00Z"),
        item("orange", State.ORANGE, days = 1),
        item("one-off-new", State.RED, group = RecurrenceGroup.SANS_CADENCE),
        item("uncertain", group = RecurrenceGroup.SANS_CADENCE, lastDone = "2026-11-10T10:00:00Z", uncertain = true),
        item("red", State.RED, days = -3),
    )

    private fun ids(rows: List<TaskListRow>) = rows.map {
        when (it) {
            is TaskListRow.Item -> it.task.task.id
            is TaskListRow.Header -> "#${it.kind}"
            is TaskListRow.UpToDateToggle -> "toggle(${it.count},${it.expanded})"
        }
    }

    @Test
    fun `blocks split what is to do from what is up to date`() {
        val blocks = taskListBlocks(tasks)
        assertEquals(listOf("red", "orange"), blocks.toDo.map { it.task.id })
        assertEquals(listOf("one-off-new", "uncertain"), blocks.withoutCadence.map { it.task.id })
        assertEquals(listOf("green", "one-off-done"), blocks.upToDate.map { it.task.id })
        assertEquals(4, blocks.toDoCount)
    }

    @Test
    fun `folded, the up-to-date tasks wait behind a counted button`() {
        assertEquals(
            listOf("red", "orange", "#WITHOUT_CADENCE", "one-off-new", "uncertain", "toggle(2,false)"),
            ids(taskListRows(taskListBlocks(tasks), showUpToDate = false, searching = false)),
        )
    }

    @Test
    fun `unfolded, they follow their header and the button hides them`() {
        assertEquals(
            listOf(
                "red", "orange", "#WITHOUT_CADENCE", "one-off-new", "uncertain",
                "#UP_TO_DATE", "green", "one-off-done", "toggle(2,true)",
            ),
            ids(taskListRows(taskListBlocks(tasks), showUpToDate = true, searching = false)),
        )
    }

    @Test
    fun `a search shows everything with its headers and no button`() {
        assertEquals(
            listOf(
                "red", "orange", "#WITHOUT_CADENCE", "one-off-new", "uncertain",
                "#UP_TO_DATE", "green", "one-off-done",
            ),
            ids(taskListRows(taskListBlocks(tasks), showUpToDate = false, searching = true)),
        )
    }

    @Test
    fun `no header above an empty block, no button when nothing is up to date`() {
        val dueOnly = listOf(item("red", State.RED, days = -1), item("orange", State.ORANGE))
        assertEquals(listOf("red", "orange"), ids(taskListRows(taskListBlocks(dueOnly), false, false)))
        val upToDateOnly = listOf(item("green"))
        assertEquals(listOf("toggle(1,false)"), ids(taskListRows(taskListBlocks(upToDateOnly), false, false)))
    }
}

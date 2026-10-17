package com.torchons.app.ui.tasklist

import com.torchons.app.data.model.Task
import com.torchons.app.util.TaskUrgencyCalculator.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of the Tasks tab, now shared with the widget (sortForTaskList).
 *
 * PATHS by which "the widget and the tab show the same order" can break, listed
 * before the cases:
 *   (1) cadence tasks: RED > ORANGE > GREEN, then most overdue first ......... COVERED
 *   (2) then never-done tasks without cadence, newest first .................. COVERED
 *   (3) then done tasks without cadence, most recently done first ............ COVERED
 *   (4) the urgency of each task (computeTasksWithUrgency) ...... not covered: it
 *       needs Resources for the countdown label; the calculator it calls has its
 *       own tests, and the function was moved unchanged from the view model.
 *   (5) the widget drawing the rows in that order ............... not covered: Glance.
 */
class TaskUrgencyListTest {

    private fun item(
        id: String,
        state: State = State.GREEN,
        days: Int = 0,
        group: RecurrenceGroup = RecurrenceGroup.CADENCE,
        createdAt: String = "",
        lastDone: String? = null,
    ) = TaskWithUrgency(
        task = Task(id = id, title = id, createdAt = createdAt),
        state = state,
        daysUntilDue = days,
        countdownLabel = "",
        categoryName = null,
        categoryColor = null,
        roomName = null,
        roomEmoji = null,
        group = group,
        lastCompletedAt = lastDone,
    )

    @Test
    fun `cadence by state then overdue, then the two blocks without cadence`() {
        val shuffled = listOf(
            item("done-old", group = RecurrenceGroup.SANS_CADENCE, lastDone = "2026-01-01"),
            item("green", State.GREEN, days = 3),
            item("never-new", group = RecurrenceGroup.SANS_CADENCE, createdAt = "2026-10-01"),
            item("orange", State.ORANGE, days = 1),
            item("red-a-little", State.RED, days = -1),
            item("done-recent", group = RecurrenceGroup.SANS_CADENCE, lastDone = "2026-10-10"),
            item("red-a-lot", State.RED, days = -9),
            item("never-old", group = RecurrenceGroup.SANS_CADENCE, createdAt = "2026-01-01"),
        )
        assertEquals(
            listOf(
                "red-a-lot", "red-a-little", "orange", "green",
                "never-new", "never-old",
                "done-recent", "done-old",
            ),
            sortForTaskList(shuffled).map { it.task.id },
        )
    }
}

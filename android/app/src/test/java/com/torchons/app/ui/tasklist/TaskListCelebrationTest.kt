package com.torchons.app.ui.tasklist

import com.torchons.app.data.model.Task
import com.torchons.app.util.TaskUrgencyCalculator.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The completion is written at tap time, so the cache turns the task green during its
 * celebration: the list holds the card as it was (TaskListCelebration.kt).
 *
 * PATHS: (1) a held task stays in « À faire » while the cache says green .... COVERED; (2) the
 * other tasks are untouched .... COVERED; (3) nothing held: the result is the same object
 * .... COVERED; (4) a held task the list does not show (context filter) is not added back
 * .... COVERED; (5) the ViewModel holding at the tap and releasing at the end of the animation:
 * not covered (viewModelScope, Hilt).
 */
class TaskListCelebrationTest {

    private fun item(id: String, state: State, days: Int) = TaskWithUrgency(
        task = Task(id = id, title = id), state = state, daysUntilDue = days, countdownLabel = "",
        categoryName = null, categoryColor = null, roomName = null, roomEmoji = null,
    )

    private fun result(vararg tasks: TaskWithUrgency) =
        FilteredResult(tasks.toList(), emptyList(), isAtHome = true, homeTasksHidden = 0)

    private val overdue = item("towels", State.RED, -3)
    private val doneNow = item("towels", State.GREEN, 7) // the same task once the write landed
    private val other = item("dishes", State.ORANGE, 1)

    @Test
    fun `a task written during its celebration keeps its card in the to-do block`() {
        val held = holdCelebrated(result(doneNow, other), mapOf("towels" to overdue))

        assertEquals(listOf("towels", "dishes"), held.blocks.toDo.map { it.task.id })
        assertEquals(emptyList<TaskWithUrgency>(), held.blocks.upToDate)
        assertEquals(listOf("towels", "dishes"), held.tasks.map { it.task.id })
    }

    @Test
    fun `without the hold the written task leaves the to-do block`() {
        val plain = result(doneNow, other)
        assertEquals(listOf("dishes"), plain.blocks.toDo.map { it.task.id })
        assertEquals(listOf("towels"), plain.blocks.upToDate.map { it.task.id })
    }

    @Test
    fun `nothing held returns the result as is`() {
        val r = result(doneNow, other)
        assertSame(r, holdCelebrated(r, emptyMap()))
    }

    @Test
    fun `a held task the list does not show is not added back`() {
        val held = holdCelebrated(result(other), mapOf("towels" to overdue))
        assertEquals(listOf("dishes"), held.tasks.map { it.task.id })
    }
}

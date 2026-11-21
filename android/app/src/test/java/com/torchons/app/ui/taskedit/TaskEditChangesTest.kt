package com.torchons.app.ui.taskedit

import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.util.DurationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator finding of 2026-11-20 (item 1): every save of the edit form sent all seven fields, so
 * the history said "changed 5 fields" for a no-op, and a form opened on stale data reverted what it
 * did not show right. `saveAction` is the one place that decides what a save sends.
 *
 * Paths by which a save can send more or less than the user changed:
 *   (1) nothing touched: no op at all ............................................. COVERED
 *   (2) one field touched: only that field, the others null ..................... COVERED (title,
 *       weight, context added, description cleared)
 *   (3) contexts emptied on purpose: an EMPTY list goes out ("detach all") ....... COVERED
 *   (4) contexts reordered: counts (the first is the main one); assignees reordered: does not ... COVERED
 *   (5) the form opened on a stale context list and the user touched the title only: the
 *       contexts are NOT in the update, so they cannot be reverted ................ COVERED
 *   (6) recurrence edited / left alone ........................................... COVERED
 *   (7) a frequency vote moved the rule: the new reference, not a pending edit ... NOT covered here
 *       (ViewModel state copy); the comparison itself is (6).
 *   (8) creating: the whole form goes out ........................................ COVERED
 * Not covered: the ViewModel's coroutine around the action, the Compose screen.
 */
class TaskEditChangesTest {

    /** A form as the loader leaves it: values filled, baseline taken. */
    private fun loaded(
        contexts: List<String> = listOf("c1", "c2"),
        assignees: List<String> = listOf("alice", "bob"),
        description: String = "Weekly",
    ): TaskEditUiState {
        val form = TaskEditUiState(
            isEditMode = true,
            title = "Vacuum",
            description = description,
            contextIds = contexts,
            assignedToIds = assignees,
            recurrenceType = "interval",
            recurrenceFieldText = "7",
            recurrenceUnit = DurationUnit.JOURS,
            weight = 3,
            tolerance = "NORMAL",
        )
        return form.copy(baseline = form.toBaseline())
    }

    private fun update(form: TaskEditUiState): UpdateTaskRequest =
        (form.saveAction("t1") as SaveAction.Update).request

    @Test
    fun `a save with nothing touched sends nothing`() {
        assertEquals(SaveAction.Nothing, loaded().saveAction("t1"))
    }

    @Test
    fun `only the touched field is sent`() {
        assertEquals(UpdateTaskRequest(title = "Vacuum the stairs"), update(loaded().copy(title = "Vacuum the stairs")))
        assertEquals(UpdateTaskRequest(weight = 5), update(loaded().copy(weight = 5)))
        assertEquals(
            UpdateTaskRequest(contextIds = listOf("c1", "c2", "c3")),
            update(loaded().copy(contextIds = listOf("c1", "c2", "c3"))),
        )
        assertEquals(UpdateTaskRequest(description = ""), update(loaded().copy(description = "  ")))
    }

    @Test
    fun `emptying the contexts sends an empty list, not null`() {
        val request = update(loaded().copy(contextIds = emptyList()))

        assertEquals(emptyList<String>(), request.contextIds)
        assertNull(request.title)
    }

    @Test
    fun `context order counts, assignee order does not`() {
        assertEquals(
            UpdateTaskRequest(contextIds = listOf("c2", "c1")),
            update(loaded().copy(contextIds = listOf("c2", "c1"))),
        )
        assertEquals(SaveAction.Nothing, loaded().copy(assignedToIds = listOf("bob", "alice")).saveAction("t1"))
    }

    @Test
    fun `a form opened on stale contexts cannot revert them from a title edit`() {
        // The cache showed no context; the server has two. The user only retitles the task.
        val staleForm = loaded(contexts = emptyList()).copy(title = "Vacuum the stairs")

        val request = update(staleForm)

        assertEquals("Vacuum the stairs", request.title)
        assertNull(request.contextIds)
        assertNull(request.assignedToIds)
    }

    @Test
    fun `recurrence is sent only when it differs from the loaded one`() {
        assertEquals(SaveAction.Nothing, loaded().copy(recurrenceFieldText = "7").saveAction("t1"))

        val request = update(loaded().copy(recurrenceFieldText = "14"))

        assertEquals(RecurrenceRule(type = "interval", everyNDays = 14), request.recurrenceRule)
        assertNull(request.title)
    }

    @Test
    fun `creating sends the whole form`() {
        val create = loaded().copy(isEditMode = false).saveAction(null) as SaveAction.Create

        assertEquals("Vacuum", create.request.title)
        assertEquals(listOf("c1", "c2"), create.request.contextIds)
        assertEquals(3, create.request.weight)
        assertTrue(create.request.recurrenceRule != null)
    }
}

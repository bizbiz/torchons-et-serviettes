package com.torchons.app.data.repository

import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.Task
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home's suggestions are a server snapshot: a task I completed that is not pushed yet must not
 * come back as a suggestion (2026-11-19), after a new ViewModel or a process death too, so the
 * filter reads the Room completions, with the same window as the duplicate check.
 *
 * PATHS by which a completed task is suggested again:
 *   (1) my completion inside the window: hidden ................................... COVERED
 *   (2) the window follows the task's recurrence, like CompletionChecks ........... COVERED
 *   (3) someone else's completion, or mine outside the window: not hidden ......... COVERED
 *   (4) no user known: nothing hidden ............................................. COVERED
 *   (5) the screen filtering with this set (HomeScreen.visibleSuggestions) and the Flow
 *       feeding it: not covered, Compose and Room; the set is the logic.
 */
class LocallyCompletedTest {

    private val now = Instant.parse("2026-11-19T10:00:00Z")

    private fun done(task: String, by: String, at: String) =
        Completion(id = "$task-$by-$at", taskId = task, completedBy = by, completedAt = at)

    private val daily = Task(id = "daily", title = "daily")
    private val weekly = Task(
        id = "weekly", title = "weekly", recurrenceRule = RecurrenceRule("interval", everyNDays = 7),
    )

    @Test
    fun `my completion inside the window hides the task`() {
        val ids = locallyCompletedTaskIds(
            listOf(daily, weekly), listOf(done("daily", "me", "2026-11-19T09:40:00Z")), "me", now,
        )
        assertEquals(setOf("daily"), ids)
    }

    @Test
    fun `the window is the task's own, 6 hours hides a weekly task, not a daily one`() {
        val completions = listOf(
            done("daily", "me", "2026-11-19T04:00:00Z"),
            done("weekly", "me", "2026-11-19T04:00:00Z"),
        )
        assertEquals(setOf("weekly"), locallyCompletedTaskIds(listOf(daily, weekly), completions, "me", now))
    }

    @Test
    fun `someone else's completion hides nothing`() {
        val theirs = listOf(done("daily", "alice", "2026-11-19T09:50:00Z"))
        assertEquals(emptySet<String>(), locallyCompletedTaskIds(listOf(daily), theirs, "me", now))
    }

    @Test
    fun `no user known hides nothing`() {
        val mine = listOf(done("daily", "me", "2026-11-19T09:50:00Z"))
        assertEquals(emptySet<String>(), locallyCompletedTaskIds(listOf(daily), mine, null, now))
    }
}

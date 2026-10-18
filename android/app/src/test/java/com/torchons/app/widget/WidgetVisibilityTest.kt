package com.torchons.app.widget

import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the widget may show (visibleOnWidget), before ranking.
 *
 * PATHS by which a task can reach the widget wrongly, listed before the cases:
 *   (1) a task assigned to the other member only ............................ COVERED
 *   (2) a task assigned to me, alone or with others, or to nobody ........... COVERED
 *   (3) no user id known (logged-out cache) shows only unassigned tasks ..... COVERED
 *   (4) off the home WiFi, a task with one presence context among several ... COVERED
 *   (5) at home (hidePresence false), presence tasks stay ................... COVERED
 *   (6) hidePresence itself (setting AND WiFi) ............... not covered: it reads
 *       DataStore and the WiFi SSID, Android only.
 *   (7) the server sending another member's task at all ...... not covered here: the
 *       todo item on solo tasks owns that test, on the backend.
 */
class WidgetVisibilityTest {

    private val home = TaskContextRef(id = "c-home", name = "Ménage", requiresPresence = true)
    private val admin = TaskContextRef(id = "c-admin", name = "Administratif")

    private fun task(id: String, assigned: List<String> = emptyList(), contexts: List<TaskContextRef> = emptyList()) =
        Task(id = id, title = id, assignedToIds = assigned, contexts = contexts)

    private val tasks = listOf(
        task("hers-only", assigned = listOf("lo")),
        task("mine-only", assigned = listOf("me")),
        task("shared", assigned = listOf("lo", "me")),
        task("nobody"),
        task("home-and-admin", assigned = listOf("me"), contexts = listOf(admin, home)),
    )

    private fun ids(list: List<Task>) = list.map { it.id }

    @Test
    fun `a task of the other member only never reaches the widget`() {
        assertEquals(
            listOf("mine-only", "shared", "nobody", "home-and-admin"),
            ids(visibleOnWidget(tasks, userId = "me", hidePresence = false)),
        )
    }

    @Test
    fun `without a user id only unassigned tasks are shown`() {
        assertEquals(listOf("nobody"), ids(visibleOnWidget(tasks, userId = null, hidePresence = false)))
    }

    @Test
    fun `off the home WiFi one presence context is enough to hide a task`() {
        assertEquals(
            listOf("mine-only", "shared", "nobody"),
            ids(visibleOnWidget(tasks, userId = "me", hidePresence = true)),
        )
    }
}

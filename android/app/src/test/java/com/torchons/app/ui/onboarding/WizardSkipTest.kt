package com.torchons.app.ui.onboarding

import com.torchons.app.data.model.CatalogContextView
import com.torchons.app.data.model.CatalogTaskContextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perimeter: the pure functions [withContextsSkipped] and [withTasksSkipped].
 *
 * - skip on step 4 -> no task selected, even if the tasks are default-selected (covered)
 * - skip on step 3 -> no context, no custom room, no task (covered)
 * - skip flag set so that a catalog reload keeps the selection empty (covered, flag only)
 * - not covered: the ViewModel wiring (skipStep -> submit), it needs a Hilt VM
 * - not covered: loadCatalog reading the flag, same reason
 */
class WizardSkipTest {

    private val contexts = listOf(
        CatalogContextView("menage", "Menage", "#000", selectable = false),
        CatalogContextView("chat", "Chat", "#000", defaultSelected = true),
        CatalogContextView("chien", "Chien", "#000", defaultSelected = true),
    )
    private val tasks = listOf(
        CatalogTaskContextView("litiere", "Litiere", contexts = listOf("chat"), defaultSelected = true),
        CatalogTaskContextView("balade", "Balade", contexts = listOf("chien"), defaultSelected = true),
        CatalogTaskContextView("brosser", "Brosser", contexts = listOf("chien")),
    )

    /** State as the wizard reaches step 4 with the catalog defaults. */
    private fun stateAtStep(step: Int) = recomputeSelection(
        WizardState(
            step = step,
            contexts = contexts,
            contextTasks = tasks,
            selectedContextKeys = setOf("chat", "chien"),
            customRooms = listOf("Garage"),
            taskOptIn = setOf("brosser"),
        ),
    )

    @Test
    fun precheckedStateHasTasks() {
        assertEquals(setOf("litiere", "balade", "brosser"), stateAtStep(4).selectedTaskKeys)
    }

    @Test
    fun skippingTasksSelectsNone() {
        val skipped = withTasksSkipped(stateAtStep(4))
        assertTrue(skipped.selectedTaskKeys.isEmpty())
        assertTrue(recomputeSelection(skipped).selectedTaskKeys.isEmpty())
    }

    @Test
    fun skippingContextsClearsContextsRoomsAndTasks() {
        val skipped = withContextsSkipped(stateAtStep(3))
        assertTrue(skipped.selectedContextKeys.isEmpty())
        assertTrue(skipped.customRooms.isEmpty())
        assertTrue(skipped.selectedTaskKeys.isEmpty())
        assertTrue(skipped.contextsSkipped)
    }
}

package com.torchons.app.ui.home

import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two flows Home reads instead of its in-memory sets (HomeLocalCompletions.kt).
 *
 * PATHS: (1) a completion that appears in Room hides its task, one that is deleted brings it back
 * .... COVERED; (2) the refresh on a finished push, not on the value stored at start, and not
 * twice for the same push .... COVERED; (3) HomeViewModel wiring the refresh to
 * TokenManager.lastSyncPushAtFlow: not covered (Hilt, DataStore).
 */
class HomeLocalCompletionsTest {

    private val now = Instant.parse("2026-11-19T10:00:00Z")

    @Test
    fun `a completion written in Room hides the suggestion, deleting it brings it back`() = runBlocking {
        val tasks = flowOf(listOf(Task(id = "t", title = "t")))
        val completions = MutableStateFlow(emptyList<Completion>())
        val flow = locallyCompletedFlow(tasks, completions, flowOf("me")) { now }

        assertEquals(emptySet<String>(), flow.first())
        completions.value = listOf(Completion("c", "t", "me", "2026-11-19T09:59:00Z"))
        assertEquals(setOf("t"), flow.first())
        completions.value = emptyList()
        assertEquals(emptySet<String>(), flow.first())
    }

    @Test
    fun `the refresh follows a finished push, not the value stored at the start`() = runBlocking {
        val pushes = pushFinished(flowOf(100L, 100L, 200L, 300L)).toList()
        assertEquals(listOf(200L, 300L), pushes)
    }

    @Test
    fun `no push since the start means no refresh`() = runBlocking {
        assertEquals(emptyList<Long?>(), pushFinished(flowOf(100L)).toList())
        assertEquals(emptyList<Long?>(), pushFinished(flowOf<Long?>(null)).toList())
    }
}

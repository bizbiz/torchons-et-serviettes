package com.torchons.app.util

import com.google.gson.JsonParser
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.util.AbsenceFreeze.Advice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val VECTORS = "shared/test-vectors/absence_freeze.json"

/**
 * Scope: the pause/keep-going split and the advice of the « Pendant une absence » sheet.
 * Covered paths:
 * - task with no context: never paused, advice = add a presence context;
 * - a single presence context: paused, advice = keep going on that context;
 * - presence + keeps-going on two different contexts: keeps going, advice = untick;
 * - several presence contexts: paused, all of them listed;
 * - keeps-going context but nothing requiring presence: untick AND add a presence context;
 * - household freeze off: nothing paused, no advice.
 * Not covered: the composables (sheet, tabs, expansion), seen in the dogfood; the Room source
 * of the tasks (observeActiveTasks), which this object receives already read.
 */
class AbsenceFreezeTest {

    private fun ctx(
        name: String,
        presence: Boolean = false,
        accrues: Boolean = false,
    ) = TaskContextRef(
        id = name,
        name = name,
        requiresPresence = presence,
        accruesWhenAway = accrues,
    )

    private fun one(contexts: List<TaskContextRef>, freezeEnabled: Boolean = true) =
        AbsenceFreeze.classify(listOf("t"), { contexts }, freezeEnabled).single()

    @Test
    fun `task without context keeps going and is told to add presence`() {
        val e = one(emptyList())
        assertFalse(e.frozen)
        assertEquals(Advice.AddPresence, e.advice)
    }

    @Test
    fun `single presence context pauses the task`() {
        val e = one(listOf(ctx("Maison", presence = true)))
        assertTrue(e.frozen)
        assertEquals(Advice.KeepGoing(listOf("Maison")), e.advice)
    }

    @Test
    fun `presence and keeps-going on different contexts keeps going`() {
        val e = one(listOf(ctx("Cuisine", presence = true), ctx("Jardin", accrues = true)))
        assertFalse(e.frozen)
        assertEquals(Advice.Pause(listOf("Jardin"), alsoNeedsPresence = false), e.advice)
    }

    @Test
    fun `several presence contexts are all listed`() {
        val e = one(listOf(ctx("Cuisine", presence = true), ctx("Maison", presence = true)))
        assertTrue(e.frozen)
        assertEquals(Advice.KeepGoing(listOf("Cuisine", "Maison")), e.advice)
    }

    @Test
    fun `keeps-going without any presence context also asks for one`() {
        val e = one(listOf(ctx("Jardin", accrues = true)))
        assertFalse(e.frozen)
        assertEquals(Advice.Pause(listOf("Jardin"), alsoNeedsPresence = true), e.advice)
    }

    /**
     * Same table as the server's `tests/test_absence_freeze_vectors.py`, which also proves the
     * table is exhaustive. A rule changed on one side only fails that side's test.
     */
    @Test
    fun `rule matches the shared table`() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, VECTORS).isFile) dir = dir.parentFile
        val file = File(requireNotNull(dir) { "$VECTORS not found above user.dir" }, VECTORS)
        val cases = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("cases")
        assertTrue("empty table", cases.size() > 0)
        for (case in cases) {
            val obj = case.asJsonObject
            val contexts = obj.getAsJsonArray("contexts").mapIndexed { i, c ->
                val o = c.asJsonObject
                ctx("c$i", o["presence"].asBoolean, o["accrues"].asBoolean)
            }
            assertEquals(obj.toString(), obj["freezes"].asBoolean, AbsenceFreeze.freezes(contexts))
        }
    }

    @Test
    fun `freeze disabled keeps everything going with no advice`() {
        val e = one(listOf(ctx("Maison", presence = true)), freezeEnabled = false)
        assertFalse(e.frozen)
        assertNull(e.advice)
    }
}

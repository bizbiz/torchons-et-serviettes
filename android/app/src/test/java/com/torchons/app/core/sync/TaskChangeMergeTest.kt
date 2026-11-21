package com.torchons.app.core.sync

import com.google.gson.Gson
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.TaskContextRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server's change_log carries PARTIAL task UPDATEs: frequency votes
 * (`{"recurrence_rule": ...}`), migration d4ec241658ca, PUT /tasks
 * (`exclude_unset=True`), relayed /sync ops (only the edited keys) and the
 * auto-archive job (`{"is_archived": true}`). Applying one must patch the
 * cached row, never rebuild it from defaults.
 *
 * Entries are parsed with Gson from raw JSON, exactly like Retrofit does, so
 * numbers arrive as Double and nested objects as LinkedTreeMap.
 *
 * Writers of `change_log` rows with table_name='tasks' (grep record_change +
 * raw INSERT in alembic), and the case covering each:
 * - gamification.py set_frequency, UPDATE {recurrence_rule}: partialRecurrenceUpdate
 * - alembic d4ec241658ca, UPDATE {recurrence_rule: calendar}: calendarRuleFromMigration
 * - tasks.py PUT, UPDATE exclude_unset subset of TaskUpdate: partialEdit, explicitNull
 * - sync.py relay of a client UPDATE (only edited keys): same shape as PUT, same cases
 * - services/archiving.py, UPDATE {is_archived}: archiveOnlyUpdate
 * - tasks.py POST / sync.py relay, INSERT (TaskCreate keys): insert_buildsFullRow
 * - UPDATE for a row absent from the cache: updateOnUnknownRow
 * non couvert : the DAO wiring in SyncWorker.applyTaskChange (getById/upsert),
 *   it needs a WorkManager context; covered by compilation only.
 * context_ids (2026-11-20): resolved from the CACHED contexts the caller passes in; an id the
 *   cache does not know is left out; a change with no `context_ids` key keeps the row's
 *   contexts (contextChange_* tests).
 */
class TaskChangeMergeTest {

    private val gson = Gson()

    private val existing = TaskEntity(
        id = "t1",
        title = "Aspirateur salon",
        description = "Sous le canape aussi",
        assignedToIds = listOf("u1", "u2"),
        recurrenceRule = RecurrenceRule(type = "interval", everyNDays = 7),
        inferredIntervalDays = 6.5,
        weight = 3,
        tolerance = "STRICT",
        isActive = true,
        isArchived = false,
        sortOrder = 4,
        createdAt = "2026-01-01T00:00:00+00:00",
        updatedAt = "2026-09-01T00:00:00+00:00",
        absentDaysSinceLastCompletion = 2,
        lastCompletedAt = "2026-09-20T10:00:00+00:00",
        contexts = listOf(TaskContextRef(id = "c1", name = "Salon")),
    )

    private fun entry(operation: String, dataJson: String?): ChangeLogEntry = gson.fromJson(
        """{"server_version": 42, "table": "tasks", "row_id": "t1",
            "operation": "$operation", "data": ${dataJson ?: "null"},
            "changed_at": "2026-09-30T12:00:00+00:00"}""",
        ChangeLogEntry::class.java,
    )

    @Test
    fun partialRecurrenceUpdate_keepsOtherFields_andUpdatesRule() {
        val change = entry(
            "UPDATE",
            """{"recurrence_rule": {"type": "monthly_variable",
                "days_per_month": {"1": 30, "2": 30, "3": 15, "4": 15, "5": 15, "6": 15,
                                   "7": 15, "8": 15, "9": 15, "10": 30, "11": 30, "12": 30}}}""",
        )

        val merged = taskEntityFromChange(existing, change)

        assertNotNull(merged)
        val expectedRule = RecurrenceRule(
            type = "monthly_variable",
            daysPerMonth = mapOf(
                "1" to 30, "2" to 30, "3" to 15, "4" to 15, "5" to 15, "6" to 15,
                "7" to 15, "8" to 15, "9" to 15, "10" to 30, "11" to 30, "12" to 30,
            ),
        )
        assertEquals(expectedRule, merged!!.recurrenceRule)
        // Everything the change did not carry is left as it was.
        assertEquals(existing.copy(recurrenceRule = expectedRule, updatedAt = merged.updatedAt), merged)
    }

    @Test
    fun calendarRuleFromMigration_isParsedWithIntegers() {
        val change = entry(
            "UPDATE",
            """{"recurrence_rule": {"type": "calendar", "months": [5], "day": 15, "grace_days": 0}}""",
        )

        val merged = taskEntityFromChange(existing, change)!!

        assertEquals(
            RecurrenceRule(type = "calendar", months = listOf(5), day = 15, graceDays = 0),
            merged.recurrenceRule,
        )
        assertEquals("Aspirateur salon", merged.title)
    }

    @Test
    fun partialEdit_patchesOnlyCarriedKeys() {
        val change = entry("UPDATE", """{"title": "Aspirateur partout", "weight": 5, "context_ids": ["c2"]}""")

        val merged = taskEntityFromChange(existing, change, knownContexts = listOf(salon, chat))!!

        assertEquals("Aspirateur partout", merged.title)
        assertEquals(5, merged.weight)
        assertEquals(existing.assignedToIds, merged.assignedToIds)
        assertEquals(existing.recurrenceRule, merged.recurrenceRule)
        assertEquals(existing.description, merged.description)
        assertEquals(existing.tolerance, merged.tolerance)
        assertEquals(listOf("c2"), merged.contexts.map { it.id })
        assertEquals(existing.createdAt, merged.createdAt)
    }

    private fun contextRow(id: String, name: String) = ContextEntity(
        id = id, name = name, color = "#112233", icon = null, emoji = null, requiresPresence = true,
        accruesWhenAway = false, sortOrder = 0, templateKey = null, version = 1, createdAt = null,
    )
    private val salon = contextRow("c1", "Salon")
    private val chat = contextRow("c2", "Chat")

    @Test
    fun contextChange_fromAnotherDevice_isShownAtOnce_inTheOrderSent() {
        val change = entry("UPDATE", """{"context_ids": ["c2", "c1"]}""")

        val merged = taskEntityFromChange(existing, change, knownContexts = listOf(salon, chat))!!

        assertEquals(listOf("c2", "c1"), merged.contexts.map { it.id })
        assertEquals(listOf("Chat", "Salon"), merged.contexts.map { it.name })
        assertEquals(true, merged.contexts.first().requiresPresence)
    }

    @Test
    fun contextChange_toNone_detachesAll_andAnUnknownIdIsLeftOut() {
        val none = taskEntityFromChange(existing, entry("UPDATE", """{"context_ids": []}"""))!!
        val unknown = taskEntityFromChange(
            existing, entry("UPDATE", """{"context_ids": ["c1", "c404"]}"""), knownContexts = listOf(salon),
        )!!

        assertEquals(emptyList<String>(), none.contexts.map { it.id })
        assertEquals(listOf("c1"), unknown.contexts.map { it.id })
    }

    @Test
    fun updateWithoutContextKey_keepsTheRowsContexts() {
        val merged = taskEntityFromChange(existing, entry("UPDATE", """{"title": "Autre"}"""), listOf(chat))!!

        assertEquals(existing.contexts, merged.contexts)
    }

    @Test
    fun explicitNullClearsNullableFields() {
        val change = entry("UPDATE", """{"description": null, "recurrence_rule": null}""")

        val merged = taskEntityFromChange(existing, change)!!

        assertNull(merged.description)
        assertNull(merged.recurrenceRule)
        assertEquals(existing.title, merged.title)
    }

    @Test
    fun archiveOnlyUpdate_togglesFlag() {
        val merged = taskEntityFromChange(existing, entry("UPDATE", """{"is_archived": true}"""))!!

        assertEquals(true, merged.isArchived)
        assertEquals(existing.title, merged.title)
    }

    @Test
    fun updateOnUnknownRow_inventsNothing() {
        assertNull(taskEntityFromChange(null, entry("UPDATE", """{"recurrence_rule": null}""")))
    }

    @Test
    fun insert_buildsFullRow_includingRecurrence() {
        val change = entry(
            "INSERT",
            """{"title": "Vitres", "description": null, "assigned_to_ids": ["u1"],
                "recurrence_rule": {"type": "interval", "every_n_days": 30},
                "weight": 2, "tolerance": "SOUPLE", "context_ids": ["c9"]}""",
        )

        val built = taskEntityFromChange(null, change)!!

        assertEquals("t1", built.id)
        assertEquals("Vitres", built.title)
        assertEquals(listOf("u1"), built.assignedToIds)
        assertEquals(RecurrenceRule(type = "interval", everyNDays = 30), built.recurrenceRule)
        assertEquals(2, built.weight)
        assertEquals("SOUPLE", built.tolerance)
        assertEquals(emptyList<String>(), built.contexts.map { it.id }) // c9 is not in the cache
        // No created_at in the payload: the server's change time is the best
        // available stand-in (an empty string would break urgency math).
        assertEquals("2026-09-30T12:00:00+00:00", built.createdAt)
    }
}

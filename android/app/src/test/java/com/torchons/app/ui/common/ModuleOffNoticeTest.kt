package com.torchons.app.ui.common

import com.google.gson.Gson
import com.torchons.app.data.model.ModuleView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perimeter: the PURE selection of "module turned off by another member" Home notices
 * (pickModuleOffNotices, moduleOffNoticeKey) and the new DTO fields.
 * Not covered: the ViewModel/DataStore acknowledgement and the Compose card (need Android).
 */
class ModuleOffNoticeTest {

    private val now = Instant.parse("2026-11-16T16:00:00Z")
    private val recent = "2026-11-16T15:41:07.123456Z"

    private fun module(
        key: String = "motivation",
        scope: String = "collective",
        enabled: Boolean = false,
        by: String? = "Alex",
        at: String? = recent,
        order: Int = 1,
    ) = ModuleView(
        key = key, scope = scope, order = order, enabled = enabled,
        title = key, pitch = "", details = "", changedByName = by, changedAt = at,
    )

    private fun pick(vararg m: ModuleView, acked: Set<String> = emptySet()) =
        pickModuleOffNotices(m.toList(), "f1", acked, now)

    @Test
    fun turnedOffByOtherRecentlyGivesNotice() {
        assertEquals(listOf("motivation"), pick(module()).map { it.key })
    }

    @Test
    fun acknowledgedGivesNothing() {
        val m = module()
        assertTrue(pick(m, acked = setOf(moduleOffNoticeKey("f1", m))).isEmpty())
    }

    @Test
    fun newChangeAfterAcknowledgementGivesNoticeAgain() {
        val old = module(at = "2026-11-10T10:00:00Z")
        val again = module(at = recent)
        assertEquals(1, pick(again, acked = setOf(moduleOffNoticeKey("f1", old))).size)
    }

    @Test
    fun acknowledgementIsPerHousehold() {
        val m = module()
        val ackedElsewhere = setOf(moduleOffNoticeKey("other-foyer", m))
        assertEquals(1, pick(m, acked = ackedElsewhere).size)
    }

    @Test
    fun enabledGivesNothing() {
        assertTrue(pick(module(enabled = true)).isEmpty())
    }

    @Test
    fun personalModuleGivesNothing() {
        assertTrue(pick(module(key = "statistics", scope = "personal")).isEmpty())
    }

    @Test
    fun missingNameGivesNothing() {
        assertTrue(pick(module(by = null)).isEmpty())
        assertTrue(pick(module(by = "  ")).isEmpty())
    }

    @Test
    fun olderThanThirtyDaysGivesNothing() {
        assertTrue(pick(module(at = "2026-10-10T00:00:00Z")).isEmpty())
    }

    @Test
    fun justInsideThirtyDaysGivesNotice() {
        assertEquals(1, pick(module(at = "2026-10-18T00:00:00Z")).size)
    }

    @Test
    fun missingOrBadDateGivesNothing() {
        assertTrue(pick(module(at = null)).isEmpty())
        assertTrue(pick(module(at = "yesterday")).isEmpty())
    }

    @Test
    fun severalNoticesFollowRegistryOrder() {
        val keys = pick(module("b", order = 2), module("a", order = 1)).map { it.key }
        assertEquals(listOf("a", "b"), keys)
    }

    @Test
    fun serverJsonFieldsAreParsedAndOldServerDefaultsToNull() {
        val withFields = Gson().fromJson(
            """{"key":"motivation","scope":"collective","order":1,"enabled":false,"title":"M",
               "changed_by_name":"Alex","changed_at":"$recent"}""",
            ModuleView::class.java,
        )
        assertEquals("Alex", withFields.changedByName)
        assertEquals(recent, withFields.changedAt)
        val old = Gson().fromJson(
            """{"key":"motivation","scope":"collective","order":1,"enabled":false,"title":"M"}""",
            ModuleView::class.java,
        )
        assertNull(old.changedByName)
        assertNull(old.changedAt)
    }
}

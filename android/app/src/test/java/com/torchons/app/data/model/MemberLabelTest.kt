package com.torchons.app.data.model

import com.torchons.app.data.repository.toFormerMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberLabelTest {

    private val active = mapOf("a" to "Alice")
    private val former = mapOf(
        "b" to FormerMember("b", "bob", "Bob", anonymized = false, leftAt = "2026-10-20T10:00:00Z"),
        "c" to FormerMember("c", "carl", "", anonymized = true, leftAt = "2026-09-01T10:00:00Z"),
    )

    private fun label(id: String) = resolveMemberLabel(id, active, former, "Ancien membre")

    @Test
    fun activeMember_plainName() {
        assertEquals(MemberLabel("Alice", struck = false), label("a"))
    }

    @Test
    fun formerMemberInReturnWindow_nameStruckThrough() {
        assertEquals(MemberLabel("Bob", struck = true), label("b"))
    }

    @Test
    fun anonymizedFormerMember_localizedLabelNotStruck() {
        assertEquals(MemberLabel("Ancien membre", struck = false), label("c"))
    }

    @Test
    fun unknownId_questionMark() {
        assertEquals(MemberLabel("?", struck = false), label("zzz"))
    }

    @Test
    fun activeWinsOverFormerEntry() {
        val l = resolveMemberLabel("a", active, former + ("a" to former.getValue("b")), "x")
        assertFalse(l.struck)
    }

    @Test
    fun userRowWithLeftAt_becomesFormerMember() {
        val u = User("b", "bob", "Bob", leftAt = "2026-10-20T10:00:00Z", anonymized = true)
        assertTrue(u.toFormerMember().anonymized)
        assertEquals(false, User("b", "bob", "Bob", leftAt = "x").toFormerMember().anonymized)
    }
}

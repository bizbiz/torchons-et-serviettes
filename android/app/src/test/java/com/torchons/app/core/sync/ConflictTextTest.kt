package com.torchons.app.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D8 (2026-11-20 audit): the sync dialog showed `stale_update`, `unique_violation` and
 * `ValueError: ...` raw. Real resources under Robolectric, French and English.
 *
 * Paths by which a conflict cause reaches the user untranslated or raw:
 *   (1) a known reason has no string, or the same string as another ............. COVERED (fr + en)
 *   (2) an unknown reason (server newer than the app) falls through to the raw code .. COVERED
 *   (3) `op_error` / `rejected` detail shown raw (`ValueError: ...`, bare class name) . COVERED
 *   (4) a server-worded message (`HTTPException: 422: ...`) is lost ................... COVERED
 * Not covered: the Compose rendering of the stored sentence; that the French strings are well
 * worded (a human reads them).
 */
@RunWith(AndroidJUnit4::class)
class ConflictTextTest {
    private val known = listOf(
        "stale_update", "unique_violation", "deleted", "not_found", "op_error", "rejected", "out_of_scope",
    )

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private fun everyKnownReasonHasItsOwnSentence() {
        val texts = known.map { conflictText(ctx(), it, null) }
        assertTrue(texts.all { it.isNotBlank() && !known.contains(it) })
        assertEquals(known.size, texts.toSet().size)
    }

    @Test
    @Config(qualifiers = "fr")
    fun `every known reason has its own French sentence`() = everyKnownReasonHasItsOwnSentence()

    @Test
    @Config(qualifiers = "en")
    fun `every known reason has its own English sentence`() = everyKnownReasonHasItsOwnSentence()

    @Test
    fun `an unknown reason gets the generic sentence, never the raw code`() {
        val text = conflictText(ctx(), "from_the_future", null)
        assertEquals(ctx().getString(R.string.sync_reason_unknown), text)
        assertFalse(text.contains("from_the_future"))
    }

    @Test
    fun `developer text in the detail is never shown`() {
        val generic = ctx().getString(R.string.sync_reason_op_error)
        assertEquals(generic, conflictText(ctx(), "op_error", "ValueError: boom"))
        assertEquals(generic, conflictText(ctx(), "op_error", "OperationalError"))
        assertEquals(
            ctx().getString(R.string.sync_reason_rejected),
            conflictText(ctx(), "rejected", "ValidationError: 1 error"),
        )
    }

    @Test
    fun `a message the server worded for the user is kept`() {
        assertEquals("Trop long", conflictText(ctx(), "rejected", "HTTPException: 422: Trop long"))
        assertEquals("Trop long", serverWordedMessage("LocalizedHTTPException: 422: Trop long"))
        assertNull(serverWordedMessage("ValueError: 422x"))
        assertNull(serverWordedMessage(null))
    }

    @Test
    fun `the detail of other reasons is ignored`() {
        assertEquals(
            ctx().getString(R.string.sync_reason_stale_update),
            conflictText(ctx(), "stale_update", "HTTPException: 409: anything"),
        )
    }
}

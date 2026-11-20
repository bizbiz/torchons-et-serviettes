package com.torchons.app.ui.taskdetail

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator finding of 2026-11-20 (item 7): an edit of a task showed the raw field name
 * `context_ids` in its history line. The task-detail card (`HistoryEventCard`) had no words for
 * it either: the field was dropped from the line and from its count.
 *
 * Paths by which "a history line names the fields in the user's words":
 *   (1) every key `/sync` lets a client write on a task has words (FR) ........................ COVERED
 *   (2) `context_ids` reads "les contextes", never its key ................................... COVERED
 *   (3) a key this version does not know has no words (not shown raw) ......................... COVERED
 * Not covered: the Stats history feed (its text is built by the SERVER, `backend/app/api/stats.py`,
 * where the same key is unlabelled: reported, not fixable from the app), and the Compose card.
 */
@RunWith(AndroidJUnit4::class)
class HistoryFieldLabelsTest {
    /** Robolectric runs in English: the labels are checked in French, the app's own language. */
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().let {
        val french = Configuration(it.resources.configuration).apply { setLocale(Locale.FRENCH) }
        it.createConfigurationContext(french)
    }

    @Test
    fun every_field_the_sync_lets_a_client_edit_has_words() {
        val syncFields = listOf(
            "title", "description", "weight", "tolerance", "is_active",
            "recurrence_rule", "assigned_to_ids", "context_ids",
        )

        syncFields.forEach { assertEquals("no label for $it", false, taskFieldLabelRes(it) == null) }
    }

    @Test
    fun the_contexts_are_named_in_words() {
        assertEquals("les contextes", context.getString(taskFieldLabelRes("context_ids")!!))
        assertEquals("le poids", context.getString(taskFieldLabelRes("weight")!!))
    }

    @Test
    fun an_unknown_key_is_not_named() {
        assertNull(taskFieldLabelRes("some_future_field"))
    }
}

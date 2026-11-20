package com.torchons.app.ui.components

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.R
import com.torchons.app.core.sync.PendingOpKind
import com.torchons.app.core.sync.RejectedSyncOp
import com.torchons.app.core.sync.describePendingOps
import com.torchons.app.core.sync.settingKeyOf
import com.torchons.app.data.local.entities.PendingOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator finding of 2026-11-20 (item 3): a queued `user_settings` UPDATE was listed as
 * "Réglage : tâche inconnue" in the sync dialog, as if a setting had a task.
 *
 * Paths by which "a setting op is named by the setting":
 *   (1) the op's key is read from its payload ({key: value}, one key) ......................... COVERED
 *   (2) the key reaches the dialog row (`describePendingOps`), no task title looked up ........ COVERED
 *   (3) every key `SettingsViewModel.updateSetting` sends has its words, in French ............ COVERED
 *   (4) an unknown key (a newer version) has no words: the dialog says "Réglage modifié" ..... COVERED
 *   (5) ops without a task (setting, prompt answer) are labelled by their kind alone .......... COVERED
 * Not covered: the Compose function `syncOpLabel` itself (it only picks among the strings above).
 */
@RunWith(AndroidJUnit4::class)
class SettingLabelsTest {
    /** Robolectric runs in English: the labels are checked in French, the app's own language. */
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().let {
        val french = Configuration(it.resources.configuration).apply { setLocale(Locale.FRENCH) }
        it.createConfigurationContext(french)
    }

    private fun settingOp(payload: String, table: String = "user_settings") = PendingOperationEntity(
        clientOpId = "s1", tableName = table, operation = "UPDATE", rowId = "u1",
        payloadJson = payload, createdAt = 1L,
    )

    @Test
    fun the_key_is_the_one_key_of_the_payload() {
        assertEquals("notif_hour", settingKeyOf(settingOp("""{"notif_hour":9}""")))
        assertNull(settingKeyOf(settingOp("""{"a":1,"b":2}""")))
        assertNull(settingKeyOf(settingOp("null")))
        assertNull("only user_settings ops are about a setting", settingKeyOf(settingOp("""{"x":1}""", "tasks")))
    }

    @Test
    fun a_setting_row_carries_its_key_and_no_task() {
        val row = describePendingOps(listOf(settingOp("""{"notif_hour":9}""")), emptyMap(), emptyMap()).single()

        assertEquals(PendingOpKind.SETTING_CHANGE, row.kind)
        assertEquals("notif_hour", row.settingKey)
        assertNull(row.taskTitle)
    }

    @Test
    fun every_key_the_settings_screen_sends_has_french_words() {
        val keys = listOf(
            "notif_daily", "notif_hour", "notif_urgent", "notif_overdue_hour", "notif_solo",
            "notif_collective_with_me", "notif_collective_without_me", "notif_today_reminders",
            "affinity_popup_enabled", "affinity_expiry_months", "fair_share_tolerance_percent",
            "gamification_enabled", "hide_home_tasks_off_wifi",
        )

        keys.forEach { assertTrue("no label for $it", settingLabelRes(it) != null) }
        assertEquals("heure du résumé quotidien", context.getString(settingLabelRes("notif_hour")!!))
        assertEquals("Réglage modifié", context.getString(R.string.sync_op_setting_generic))
    }

    @Test
    fun an_unknown_or_missing_key_has_no_words() {
        assertNull(settingLabelRes("some_future_setting"))
        assertNull(settingLabelRes(null))
    }

    @Test
    fun ops_without_a_task_are_named_by_their_kind_alone() {
        assertTrue(PendingOpKind.SETTING_CHANGE in KINDS_WITHOUT_TASK)
        assertTrue(PendingOpKind.PROMPT_ANSWER in KINDS_WITHOUT_TASK)
        assertTrue(PendingOpKind.TASK_EDIT !in KINDS_WITHOUT_TASK)
        assertNull(RejectedSyncOp(PendingOpKind.SETTING_CHANGE, null, null, 1L).settingKey)
    }
}

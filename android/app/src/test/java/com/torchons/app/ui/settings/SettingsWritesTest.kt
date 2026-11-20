package com.torchons.app.ui.settings

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.BonusConfig
import com.torchons.app.data.repository.FakeLocalDb
import com.torchons.app.data.repository.LocalMutation
import com.torchons.app.data.repository.SettingsLedger
import com.torchons.app.data.repository.overlayPendingSettings
import com.torchons.app.ui.common.UserError
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * D7 (2026-11-20 audit), the settings screens. `updateSetting` was a REST call whose failure was
 * only logged: a toggle flipped offline looked saved and was lost. Home Wi-Fi, bonus thresholds,
 * rename and freeze showed the new value and never rolled back.
 *
 * Paths by which "a member setting is never lost, an online-only one is never faked":
 *   (1) a member setting changed OFFLINE: queued as a `user_settings` UPDATE, no REST call, no
 *       error shown, DataStore copy refreshed, sync requested ............................. COVERED
 *   (2) the same with the server answering 500: still no REST call, still queued ........... COVERED
 *   (3) numbers keep their type through the queue (`9` stays an Int, the server writes the column
 *       raw) ................................................................................ COVERED
 *   (4) a reload while the op is still queued shows what the member chose, not the server's
 *       old value (overlay, oldest first) ................................................. COVERED
 *   (5) home Wi-Fi names (REST only): refused / unreachable -> list put back AND error shown;
 *       accepted -> kept ................................................................... COVERED
 *   (6) a bonus threshold: refused -> sliders reset AND error shown; accepted -> new config ... COVERED
 * Not covered: the Compose controls (disabled offline, `LocalIsOnline`); `userId` null (not signed in).
 */
class SettingsWritesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** An ApiService answering [answer] to every call, and counting them. */
    private class Api(val calls: AtomicInteger = AtomicInteger(), val answer: () -> Any?) {
        val service: ApiService = Proxy.newProxyInstance(
            ApiService::class.java.classLoader, arrayOf(ApiService::class.java),
        ) { _, _, _ ->
            calls.incrementAndGet()
            answer()
        } as ApiService
    }

    private fun failing(code: Int) = Api { Response.error<Any>(code, "{}".toResponseBody()) }

    private fun unreachable() = Api { throw IOException("no route") }

    private class Rig(api: Api, scope: CoroutineScope) {
        val db = FakeLocalDb()
        val syncs = AtomicInteger()
        val settings = MutableStateFlow(UserSettingsState(homeSsids = listOf("Home"), isLoaded = true))
        val bonus = MutableStateFlow<BonusConfig?>(BonusConfig())
        val synced = mutableListOf<UserSettingsState>()
        val writes = SettingsWrites(
            scope = scope,
            api = api.service,
            writer = SettingsLedger(LocalMutation(db, scope, { syncs.incrementAndGet() }, { "home" })),
            userId = { "u1" },
            settings = settings,
            bonusConfig = bonus,
            syncLocal = { synced += it },
            errorOf = { UserError("translated: ${it.javaClass.simpleName}") },
        )
    }

    @Test
    fun `a setting changed offline is queued, not sent, and not reported`() = runBlocking {
        val api = unreachable()
        val rig = Rig(api, scope)
        rig.settings.value = rig.settings.value.copy(notifHour = 7)

        rig.writes.updateSetting("notif_hour", 7).join()

        assertEquals("the change is queued for /sync", 1, rig.db.ops.size)
        val op = rig.db.ops.single()
        assertEquals("user_settings", op.tableName)
        assertEquals("UPDATE", op.operation)
        assertEquals("u1", op.rowId)
        assertEquals("""{"notif_hour":7}""", op.payloadJson)
        assertEquals("home", op.foyerId)
        assertEquals("the REST route is not called", 0, api.calls.get())
        assertEquals(1, rig.syncs.get())
        assertNull("nothing for the member to see", rig.writes.actionError.value)
        assertEquals("the DataStore copy follows", 7, rig.synced.single().notifHour)
    }

    @Test
    fun `a setting is queued whatever the server would answer`() = runBlocking {
        val api = failing(500)
        val rig = Rig(api, scope)

        rig.writes.updateSetting("notif_daily", false).join()

        assertEquals("the change is queued for /sync", 1, rig.db.ops.size)
        assertEquals("""{"notif_daily":false}""", rig.db.ops.single().payloadJson)
        assertEquals(0, api.calls.get())
    }

    @Test
    fun `the queue payload keeps whole numbers whole`() {
        val op = PendingOperationEntity(
            "c1", "user_settings", "UPDATE", "u1", """{"notif_hour":9,"notif_daily":true}""", 1L,
        )
        val sent = com.torchons.app.core.sync.opPayload(op)!!
        // 9, not 9.0: /sync writes the integer column with a raw setattr.
        assertEquals(9L, sent["notif_hour"])
        assertEquals(true, sent["notif_daily"])
    }

    @Test
    fun `a reload shows what the member chose while the op is still queued`() {
        fun op(id: String, createdAt: Long, payload: String) =
            PendingOperationEntity(id, "user_settings", "UPDATE", "u1", payload, createdAt)
        val server = mapOf<String, Any?>("notif_hour" to 9.0, "notif_daily" to true, "foyer_member_count" to 2.0)
        val queue = listOf(
            op("b", 20L, """{"notif_hour":11}"""),
            op("a", 10L, """{"notif_hour":7,"notif_daily":false}"""),
            PendingOperationEntity("t", "tasks", "UPDATE", "t1", """{"notif_hour":1}""", 30L),
        )

        val shown = userSettingsFromServer(overlayPendingSettings(server, queue))

        assertEquals("the newest queued value wins", 11, shown.notifHour)
        assertEquals(false, shown.notifDaily)
        assertEquals("what is not queued is the server's", 2, shown.foyerMemberCount)
    }

    @Test
    fun `home Wi-Fi refused is put back and told`() = runBlocking {
        for (api in listOf(failing(403), failing(500), unreachable())) {
            val rig = Rig(api, scope)

            rig.writes.setHomeSsids(listOf("Home", "Cafe")).join()

            assertEquals(listOf("Home"), rig.settings.value.homeSsids)
            assertNotNull(rig.writes.actionError.value)
            assertEquals(listOf("Home"), rig.synced.last().homeSsids)
        }
    }

    @Test
    fun `home Wi-Fi accepted is kept and nothing is told`() = runBlocking {
        val rig = Rig(Api { Response.success(mapOf<String, Any>()) }, scope)

        rig.writes.setHomeSsids(listOf("Home", "Cafe")).join()

        assertEquals(listOf("Home", "Cafe"), rig.settings.value.homeSsids)
        assertNull(rig.writes.actionError.value)
    }

    @Test
    fun `a refused bonus threshold resets the sliders and is told`() = runBlocking {
        val rig = Rig(failing(422), scope)

        rig.writes.updateBonusConfig("bonus_pct", 0.9f).join()

        assertEquals(1, rig.writes.bonusResets.value)
        assertNotNull(rig.writes.actionError.value)
        assertEquals(BonusConfig(), rig.bonus.value)
    }

    @Test
    fun `an accepted bonus threshold shows the server's config`() = runBlocking {
        val updated = BonusConfig(bonusPct = 0.9f)
        val rig = Rig(Api { Response.success(updated) }, scope)

        rig.writes.updateBonusConfig("bonus_pct", 0.9f).join()

        assertEquals(updated, rig.bonus.value)
        assertEquals(0, rig.writes.bonusResets.value)
        assertNull(rig.writes.actionError.value)
    }
}

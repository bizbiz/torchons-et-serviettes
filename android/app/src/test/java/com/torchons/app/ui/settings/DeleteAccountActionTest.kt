package com.torchons.app.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Scope: [DeleteAccountAction] only (the order deleteMe -> wipe, and the failure paths).
 * Paths by which "nothing deleted locally on failure" could be violated:
 *  - server answers non-2xx: covered (http error test).
 *  - request throws (offline, timeout): covered (network failure test).
 *  - double tap sends two requests: covered (Deleting guard test).
 *  - not covered: the Compose dialog and the navigation to Welcome, no UI test infrastructure
 *    in src/test (JVM only); DeviceRevocationMonitor.wipeNow itself, needs Room + DataStore.
 *  - not covered: a DEVICE_REVOKED answer to deleteMe, handled by DeviceRevocationInterceptor.
 */
class DeleteAccountActionTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun httpError(code: Int): Response<Unit> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    @Test
    fun `wipes locally only after the server accepted the deletion`() = runBlocking {
        val calls = mutableListOf<String>()
        val action = DeleteAccountAction(
            scope,
            deleteMe = { calls += "deleteMe"; Response.success(Unit) },
            wipe = { calls += "wipe" },
        )
        action.run()
        assertEquals(listOf("deleteMe", "wipe"), calls)
        assertEquals(DeleteAccountState.Done, action.state.value)
    }

    @Test
    fun `http error keeps the user signed in and wipes nothing`() = runBlocking {
        var wiped = false
        val action = DeleteAccountAction(scope, deleteMe = { httpError(500) }, wipe = { wiped = true })
        action.run()
        assertEquals(false, wiped)
        assertEquals(DeleteAccountState.Failed, action.state.value)
    }

    @Test
    fun `network failure keeps the user signed in and wipes nothing`() = runBlocking {
        var wiped = false
        val action = DeleteAccountAction(
            scope,
            deleteMe = { throw IOException("offline") },
            wipe = { wiped = true },
        )
        action.run()
        assertEquals(false, wiped)
        assertEquals(DeleteAccountState.Failed, action.state.value)
    }

    @Test
    fun `a second tap while deleting does not send a second request`() = runBlocking {
        var calls = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val action = DeleteAccountAction(
            scope,
            deleteMe = { calls++; gate.await(); Response.success(Unit) },
            wipe = {},
        )
        action.start()
        action.start()
        assertEquals(1, calls)
        assertEquals(DeleteAccountState.Deleting, action.state.value)
        gate.complete(Unit)
        assertEquals(DeleteAccountState.Done, action.state.value)
    }
}

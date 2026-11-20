package com.torchons.app.data.repository

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 (2026-11-20 audit): token switch, cache wipe and refresh ran in the screens' ViewModel scope;
 * leaving the screen between the switch and the wipe left the new token with the old household's
 * cache.
 *
 * Paths by which "the household change is never cut in the middle, and never half-done":
 *   (1) the order: token, then wipe, then refresh ............................................ COVERED
 *   (2) the token change fails: no wipe, no refresh, the failure returned ................... COVERED
 *   (3) the caller is cancelled after the token change: wipe and refresh still run ........... COVERED
 *   (4) the wipe fails: the failure is returned, the refresh does not run ................... COVERED
 *   (5) two changes at once do not interleave ................................................ COVERED
 * Not covered: the real token switch (AuthRepository, Retrofit), the real refreshes
 * (HouseholdSwitcher.refreshAll: five repository calls), the sheet and the leave flow
 * (Compose, ViewModels); the wipe itself is HouseholdWipeRoomTest.
 */
class HouseholdSwitchRunnerTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun runner(log: MutableList<String>, wipe: suspend () -> Unit = { log += "wipe" }) =
        HouseholdSwitchRunner(appScope, wipe = wipe, refresh = { log += "refresh" })

    @Test
    fun `token, then wipe, then refresh`() = runBlocking {
        val log = CopyOnWriteArrayList<String>()

        val result = runner(log).run { log += "token"; Result.success(Unit) }

        assertTrue(result.isSuccess)
        assertEquals(listOf("token", "wipe", "refresh"), log)
    }

    @Test
    fun `a failed token change wipes nothing and returns the failure`() = runBlocking {
        val log = CopyOnWriteArrayList<String>()

        val result = runner(log).run { log += "token"; Result.failure(IllegalStateException("401")) }

        assertEquals("401", result.exceptionOrNull()?.message)
        assertEquals(listOf("token"), log)
    }

    @Test
    fun `a caller that leaves right after the token change does not cut the sequence`() = runBlocking {
        val log = CopyOnWriteArrayList<String>()
        val tokenChanged = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val waiting = screen.launch {
            runner(log).run { log += "token"; tokenChanged.complete(Unit); proceed.await(); Result.success(Unit) }
        }
        withTimeout(5_000) { tokenChanged.await() }
        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() // the screen is left
        assertTrue(waiting.isCancelled)
        proceed.complete(Unit)
        withTimeout(5_000) { while (log.size < 3) kotlinx.coroutines.delay(10) }

        assertEquals(listOf("token", "wipe", "refresh"), log)
    }

    @Test
    fun `a failing wipe is returned and the refresh does not run`() = runBlocking {
        val log = CopyOnWriteArrayList<String>()

        val result = runner(log, wipe = { log += "wipe"; error("disk full") }).run { Result.success(Unit) }

        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertEquals(listOf("wipe"), log)
    }

    @Test
    fun `two changes at once run one after the other`() = runBlocking {
        val log = CopyOnWriteArrayList<String>()
        val release = CompletableDeferred<Unit>()
        val r = runner(log)

        val first = async(Dispatchers.Default) { r.run { log += "token1"; release.await(); Result.success(Unit) } }
        withTimeout(5_000) { while (log.isEmpty()) kotlinx.coroutines.delay(10) }
        val second = async(Dispatchers.Default) { r.run { log += "token2"; Result.success(Unit) } }
        kotlinx.coroutines.delay(100)
        assertEquals(listOf("token1"), log.toList()) // the second waits for the first
        release.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }

        assertEquals(listOf("token1", "wipe", "refresh", "token2", "wipe", "refresh"), log)
    }
}

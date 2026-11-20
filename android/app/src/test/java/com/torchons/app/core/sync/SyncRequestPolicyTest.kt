package com.torchons.app.core.sync

import androidx.work.WorkInfo.State
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Emulator finding of 2026-11-20 (item 2): `requestSync` used REPLACE, so each write or reconnect
 * cancelled the running pass and started again; a vote request already on the wire was sent a
 * second time, concurrently, and the server answered 500.
 *
 * Paths by which "a request already in flight is not sent twice, a new write is still pushed":
 *   (1) a RUNNING pass is never replaced: the write is chained behind it ........................ COVERED
 *   (2) a pass already chained behind the running one absorbs the next writes (no chain of N) ... COVERED
 *   (3) nothing there, a finished pass, a pass in backoff or waiting for the network: REPLACE,
 *       as before, so a write never waits for a backoff that can last hours ..................... COVERED
 *   (4) two passes (one-shot + periodic) never run at the same time in the process ............. COVERED
 * Not covered: WorkManager itself (the mapping of each action to its ExistingWorkPolicy is two
 * lines in `SyncTrigger.requestSync`, read in the diff) and the "chain waits behind a pass that
 * ends in `retry`" case, which is deduced from WorkManager's documentation, not measured.
 */
class SyncRequestPolicyTest {
    @Test
    fun a_running_pass_is_chained_after_never_replaced() {
        assertEquals(SyncRequestAction.CHAIN_AFTER_RUNNING, syncRequestAction(listOf(State.RUNNING)))
    }

    @Test
    fun a_second_write_during_the_pass_finds_the_chained_pass_and_adds_none() {
        val states = listOf(State.RUNNING, State.BLOCKED)

        assertEquals(SyncRequestAction.ALREADY_CHAINED, syncRequestAction(states))
    }

    @Test
    fun nothing_running_starts_a_pass_now() {
        val idle = listOf(
            emptyList(),
            listOf(State.SUCCEEDED),
            listOf(State.FAILED),
            listOf(State.CANCELLED),
            listOf(State.ENQUEUED),
        )

        idle.forEach { assertEquals(it.toString(), SyncRequestAction.START_NOW, syncRequestAction(it)) }
    }

    @Test
    fun two_passes_never_overlap() = runBlocking {
        val inside = AtomicInteger()
        val overlaps = AtomicInteger()

        List(4) {
            async(kotlinx.coroutines.Dispatchers.Default) {
                syncPassLock.withLock {
                    if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
                    delay(20)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(0, overlaps.get())
    }
}

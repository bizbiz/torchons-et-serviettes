package com.torchons.app.ui.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9 (2026-11-20 audit): « Me déconnecter et couper cet appareil » wipes every local table, the
 * queue of unsent changes included, and its confirmation never said so. The dialog now shows the
 * count of waiting changes and offers « Synchroniser d'abord »: `syncBeforeSignOut` is that wait.
 *
 * Paths by which "the user signs out knowing what is lost":
 *   (1) the queue empties after the sync request: true, the request was made ONCE ........ COVERED
 *   (2) the queue is already empty: true at once ......................................... COVERED
 *   (3) the queue never empties (offline, an op refused): false after the wait, not for ever ... COVERED
 *   (4) the queue shrinks but is not empty at the end of the wait: false ................... COVERED
 * Not covered: the dialog (Compose) and the ViewModel's use of the result (SignOutSyncState).
 */
class SignOutSyncTest {
    @Test
    fun `the queue emptying after the request is a success, requested once`() = runBlocking {
        val pending = MutableStateFlow(3)
        var requests = 0

        val emptied = syncBeforeSignOut(pending, requestSync = { requests++; pending.value = 0 }, timeoutMs = 2_000)

        assertTrue(emptied)
        assertEquals(1, requests)
    }

    @Test
    fun `an already empty queue is a success`() = runBlocking {
        assertTrue(syncBeforeSignOut(MutableStateFlow(0), requestSync = {}, timeoutMs = 2_000))
    }

    @Test
    fun `a queue that never empties gives up after the wait`() = runBlocking {
        val pending = MutableStateFlow(2)
        assertFalse(syncBeforeSignOut(pending, requestSync = {}, timeoutMs = 100))
    }

    @Test
    fun `a queue that only shrinks is not a success`() = runBlocking {
        val pending = MutableStateFlow(5)
        val emptied = syncBeforeSignOut(pending, requestSync = { pending.value = 2 }, timeoutMs = 100)
        assertFalse(emptied)
    }

    @Test
    fun `the wait follows the queue while it is running`() = runBlocking {
        val pending = MutableStateFlow(2)
        var result: Boolean? = null
        val job = launch { result = syncBeforeSignOut(pending, requestSync = {}, timeoutMs = 5_000) }
        pending.value = 1
        pending.value = 0
        job.join()
        assertEquals(true, result)
    }
}

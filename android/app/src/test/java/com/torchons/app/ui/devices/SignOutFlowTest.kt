package com.torchons.app.ui.devices

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator re-test of 2026-11-20 (item 2): Settings "Déconnexion" signed out on ONE tap
 * (`clearAuthData`), skipping the pending-changes warning "My devices" has. Both now hold a
 * [SignOutFlow]; the dialog (SignOutDialog) reads its state.
 *
 * Paths by which a sign-out can drop unsent changes unseen:
 *   (1) the flow runs the sign-out ONLY on `confirm`, never at construction or on "sync first" ... COVERED
 *   (2) "sync first" with changes queued: asks for a pass, the state follows the queue ........ COVERED
 *   (3) "sync first" that cannot empty the queue: GAVE_UP, and the sign-out still waits for the
 *       user's confirmation ................................................................... COVERED
 *   (4) the dialog is dismissed: the state resets, nothing signed out ......................... COVERED
 *   (5) a NEW caller of `logout` / `clearAuthData` / `wipeNow` outside the flow: a source scan
 *       fails the build until it is added to the list below, with its reason ................. COVERED
 * Not covered: the Compose dialog and the screens' wiring (compiled, not rendered here); the
 * server-forced wipe (DeviceRevocationMonitor.onRevoked) which cannot ask anything.
 */
class SignOutFlowTest {

    private fun scope() = CoroutineScope(Job() + Dispatchers.Default)

    @Test
    fun `nothing is signed out until the user confirms`() = runBlocking {
        val scope = scope()
        val pending = MutableStateFlow(2)
        var signedOut = 0
        var syncRequests = 0
        val flow = SignOutFlow(scope, pending, { syncRequests++ }, { signedOut++ }, waitMs = 200)

        flow.syncFirst()
        withTimeout(2_000) { flow.syncState.first { it == SignOutSyncState.GAVE_UP } }

        assertEquals(0, signedOut)
        assertEquals(1, syncRequests)
        assertEquals(SignOutSyncState.GAVE_UP, flow.syncState.value)

        var navigated = false
        flow.confirm { navigated = true }
        withTimeout(2_000) { while (!navigated) kotlinx.coroutines.yield() }

        assertEquals(1, signedOut)
        assertEquals(SignOutSyncState.IDLE, flow.syncState.value)
        scope.cancel()
    }

    @Test
    fun `sync first that empties the queue ends idle, not signed out`() = runBlocking {
        val scope = scope()
        val pending = MutableStateFlow(3)
        var signedOut = 0
        val flow = SignOutFlow(scope, pending, { pending.value = 0 }, { signedOut++ }, waitMs = 2_000)

        flow.syncFirst()
        withTimeout(2_000) { flow.syncState.first { it == SignOutSyncState.IDLE && pending.value == 0 } }

        assertEquals(0, signedOut)
        scope.cancel()
    }

    @Test
    fun `dismissing the dialog resets the state`() = runBlocking {
        val scope = scope()
        val flow = SignOutFlow(scope, MutableStateFlow(1), {}, {}, waitMs = 100)
        flow.syncFirst()
        withTimeout(2_000) { flow.syncState.first { it == SignOutSyncState.GAVE_UP } }

        flow.reset()

        assertEquals(SignOutSyncState.IDLE, flow.syncState.value)
        scope.cancel()
    }

    /**
     * Where the auth data / the local tables may be dropped. Each entry says why it is not a
     * "user signs out" path that needs the warning. A new line here is a decision, not a fix.
     */
    private val allowedCallers = mapOf(
        "ui/settings/SettingsViewModel.kt" to "behind SignOutFlow (Settings Sign out)",
        "ui/devices/DevicesViewModel.kt" to "behind SignOutFlow (My devices)",
        "ui/settings/SettingsFoyerActions.kt" to
            "leaving the LAST household: its own confirmation, and the queued ops are for a " +
            "household the user just left (the server drops them as out_of_scope)",
        "core/network/DeviceRevocationMonitor.kt" to "the server cut this device: nothing to ask",
        "data/repository/AuthRepository.kt" to "the repository method itself",
        "data/local/TokenManager.kt" to "the definition of clearAuthData",
    )

    @Test
    fun `no screen signs out outside the SignOutFlow`() {
        val root = File("src/main/java/com/torchons/app")
        assertTrue("run from the module dir: ${root.absolutePath}", root.isDirectory)
        val pattern = Regex("""\.logout\(\)|clearAuthData\(\)|wipeNow\(\)""")

        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val unexpected = callers.filter { it !in allowedCallers }

        assertTrue(
            "These files sign out or wipe: route them through SignOutFlow (confirmation + pending " +
                "count + sync first), or add them to allowedCallers with the reason: $unexpected",
            unexpected.isEmpty(),
        )
        // The scan finds something at all (a regex that matches nothing would pass for ever).
        assertTrue("scan saw nothing: $callers", callers.size >= 4)
    }
}

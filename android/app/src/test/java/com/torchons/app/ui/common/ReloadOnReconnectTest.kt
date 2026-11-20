package com.torchons.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator finding of 2026-11-20 (item 5): "Mes appareils" kept "Impossible de joindre le serveur"
 * after the network was back, until its own "Réessayer" was tapped; the Stats cards (no button at
 * all) did the same. `ReloadOnReconnect` reloads them when `LocalIsOnline` goes false -> true.
 *
 * Paths by which "a screen in error recovers by itself":
 *   (1) offline -> online with the screen in error: reload ................................ COVERED
 *   (2) offline -> online with no error on screen: nothing to reload ..................... COVERED
 *   (3) already online, in error (a server error, not the network): NO reload, so a 500 never
 *       loops reload -> error -> reload ................................................... COVERED
 *   (4) online -> offline, or offline -> offline: no reload ............................... COVERED
 * Not covered: the Compose effect that feeds it (`LaunchedEffect` on `LocalIsOnline`), and
 * `BackendHealthMonitor`'s own flip to Online (a poll or any successful call).
 */
class ReloadOnReconnectTest {
    @Test
    fun coming_back_online_with_an_error_reloads() {
        assertTrue(shouldReloadOnReconnect(wasOnline = false, isOnline = true, hasError = true))
    }

    @Test
    fun coming_back_online_without_an_error_does_not() {
        assertFalse(shouldReloadOnReconnect(wasOnline = false, isOnline = true, hasError = false))
    }

    @Test
    fun an_error_while_staying_online_never_loops() {
        assertFalse(shouldReloadOnReconnect(wasOnline = true, isOnline = true, hasError = true))
    }

    @Test
    fun going_offline_or_staying_offline_does_not_reload() {
        assertFalse(shouldReloadOnReconnect(wasOnline = true, isOnline = false, hasError = true))
        assertFalse(shouldReloadOnReconnect(wasOnline = false, isOnline = false, hasError = true))
    }
}

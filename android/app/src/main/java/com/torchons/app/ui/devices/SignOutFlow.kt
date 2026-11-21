package com.torchons.app.ui.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * THE way out of the app, for every screen that signs the user out ("My devices", Settings).
 *
 * A sign-out used to be one tap in Settings (`clearAuthData` straight away) while "My devices" had
 * the pending-changes warning (D9, 2026-11-20 audit; emulator re-test of the same day). Both now
 * hold one of these and expose nothing else: the screen shows [SignOutDialog] with the live
 * pending count and [syncState], "Sync first" calls [syncFirst], and only [confirm] runs
 * [signOut]. No ViewModel offers a sign-out that skips the dialog, and a ViewModel keeps its
 * `AuthRepository.logout` behind this class.
 *
 * Plain Kotlin on purpose (scope, count, sync request and sign-out are passed in): the whole
 * sequence is tested without Hilt.
 */
internal class SignOutFlow(
    private val scope: CoroutineScope,
    private val pendingCount: Flow<Int>,
    private val requestSync: () -> Unit,
    private val signOut: suspend () -> Unit,
    private val waitMs: Long = SIGN_OUT_SYNC_WAIT_MS,
) {
    private val _syncState = MutableStateFlow(SignOutSyncState.IDLE)

    /** Where "sync first" stands; the dialog shows it through [shownSignOutSyncState]. */
    val syncState: StateFlow<SignOutSyncState> = _syncState.asStateFlow()

    init {
        // The queue emptied by itself after a failed "sync first": forget the failure, or it comes
        // back the next time a change is queued (the dialog already hides it at a count of zero).
        scope.launch {
            pendingCount.collect { count -> _syncState.value = shownSignOutSyncState(_syncState.value, count) }
        }
    }

    /** "Sync first": a pass of the worker, waited for until the queue is empty or the wait runs out. */
    fun syncFirst() {
        if (_syncState.value == SignOutSyncState.SYNCING) return
        _syncState.value = SignOutSyncState.SYNCING
        scope.launch {
            val emptied = syncBeforeSignOut(pendingCount, requestSync, waitMs)
            _syncState.value = if (emptied) SignOutSyncState.IDLE else SignOutSyncState.GAVE_UP
        }
    }

    /** The dialog was closed: a later one starts clean. */
    fun reset() {
        _syncState.value = SignOutSyncState.IDLE
    }

    /** The user confirmed in the dialog: signs out, then [onSignedOut] (navigation). */
    fun confirm(onSignedOut: () -> Unit = {}) {
        reset()
        scope.launch {
            signOut()
            onSignedOut()
        }
    }
}

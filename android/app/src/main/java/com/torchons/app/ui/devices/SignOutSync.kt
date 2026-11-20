package com.torchons.app.ui.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where the "sync first" button of the sign-out dialog stands. */
enum class SignOutSyncState { IDLE, SYNCING, GAVE_UP }

/**
 * What the dialog says about the sync, given how many changes are still queued. "The sync did not
 * finish" (GAVE_UP) is about the queue: once it is empty (the network came back and the worker
 * emptied it by itself) the message is no longer true, and the red warning that these changes
 * "will be lost" must go with it. It stays while changes remain, and the count line updates live.
 */
internal fun shownSignOutSyncState(state: SignOutSyncState, pendingCount: Int): SignOutSyncState =
    if (state == SignOutSyncState.GAVE_UP && pendingCount == 0) SignOutSyncState.IDLE else state

/** How long the dialog waits for the queue to empty before it says the sync did not finish. */
internal const val SIGN_OUT_SYNC_WAIT_MS = 20_000L

/**
 * "Sign out and cut this device" wipes every local table, the queue of unsent changes included
 * (D9, 2026-11-20 audit: offline taps vanished with no warning). Before it, the user may sync:
 * asks the worker for a pass, then waits until [pendingCount] reaches zero.
 *
 * Returns true when the queue emptied, false when [timeoutMs] ran out first (offline, the server
 * refusing an op: the queue stays, and the dialog says so instead of waiting for ever). Cancelling
 * the caller (the dialog was closed) stops the wait, not the sync.
 */
internal suspend fun syncBeforeSignOut(
    pendingCount: Flow<Int>,
    requestSync: () -> Unit,
    timeoutMs: Long = SIGN_OUT_SYNC_WAIT_MS,
): Boolean {
    requestSync()
    return withTimeoutOrNull(timeoutMs) { pendingCount.first { it == 0 } } != null
}

package com.torchons.app.ui.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where the "sync first" button of the sign-out dialog stands. */
enum class SignOutSyncState { IDLE, SYNCING, GAVE_UP }

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

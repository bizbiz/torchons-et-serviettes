package com.torchons.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a completions refresh from applying an answer that is older than a push.
 *
 * The sequence this exists for (S2, found while auditing 72ae485):
 *  1. `GET /completions` leaves the phone and the server answers it BEFORE `/sync` commits the
 *     user's completion, so the answer does not contain it;
 *  2. `/sync` commits, `SyncWorker` receives the answer and deletes the pending op;
 *  3. the stale GET answer reaches `CompletionDao.replaceAll` / `replaceForTask`. They only spare
 *     rows whose op is STILL pending (`serverRowsToApply`), and this one no longer is: the row is
 *     deleted, and the user sees the completion vanish until the next refresh.
 * An applied DELETE or UPDATE fails the same way in the other direction: the stale answer brings
 * the deleted row back, or reverts the edit. The collective write ([CollectiveCompletionLedger])
 * and completions the worker receives from other devices change the cache the same way.
 *
 * So every change of the completions cache that a refresh could not have seen is a SETTLING
 * ([settling]) and bumps an epoch; a refresh reads the epoch before its request and applies its
 * answer only if the epoch is still the same ([fetchThenApply]). When it moved, the answer is
 * dropped and the request is made once more.
 *
 * The same mechanism guards the TASKS cache ([tasks], a second [RefreshEpoch]): an offline-created
 * task, edit or delete whose op the worker settled must not be erased by a `GET /tasks` answered
 * before the push (D1, 2026-11-20 audit). The two scopes bump separately: a completion push does
 * not void a tasks refresh.
 *
 * Why this never makes the worker wait on the network: the lock of a [RefreshEpoch] is only ever
 * held around LOCAL work -- the worker's queue deletes, a refresh's Room transaction. The refresh's
 * request runs outside it, and a settling is never done inside an apply (the lock is not
 * reentrant). Nobody takes the lock while holding Room's write lock, so the two cannot deadlock.
 *
 * Why a lock at all: epoch check, pending-rows read and delete must be one step with respect to
 * the worker's delete-and-bump. Without it a refresh could read "op still pending", the worker
 * could settle it, and the refresh would then delete the row it believed protected.
 */
@Singleton
class CompletionRefreshGuard @Inject constructor() {
    private val completions = RefreshEpoch()

    /** The guard of the tasks cache (`TaskRepository.refreshTasks`, task ops settled by the worker). */
    val tasks = RefreshEpoch()

    /** The epoch a completions refresh captures BEFORE it sends its request. */
    fun currentEpoch(): Long = completions.current()

    /** See [RefreshEpoch.settling]: a change to the completions cache a refresh could not have seen. */
    suspend fun <T> settling(block: suspend () -> T): T = completions.settling(block)

    /** [settling] when [condition] holds, plain [block] otherwise (a batch with no completion in it). */
    suspend fun <T> settlingIf(condition: Boolean, block: suspend () -> T): T =
        completions.settlingIf(condition, block)

    /** Runs [apply] only if no completions settling happened since [captured]; false when refused. */
    suspend fun applyIfUnchanged(captured: Long, apply: suspend () -> Unit): Boolean =
        completions.applyIfUnchanged(captured, apply)

    /** One completions refresh, see [RefreshEpoch.fetchThenApply]. */
    suspend fun <T : Any> fetchThenApply(fetch: suspend () -> T, apply: suspend (T) -> Unit): T? =
        completions.fetchThenApply(fetch, apply)
}

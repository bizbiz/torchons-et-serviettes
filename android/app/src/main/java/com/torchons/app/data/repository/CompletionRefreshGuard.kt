package com.torchons.app.data.repository

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Why this never makes the worker wait on the network: [lock] is only ever held around LOCAL work
 * -- the worker's queue deletes, a refresh's Room transaction. The refresh's request runs outside
 * it, and a settling is never done inside an apply (the lock is not reentrant). Nobody takes the
 * lock while holding Room's write lock, so the two cannot deadlock.
 *
 * Why a lock at all: epoch check, pending-rows read and delete must be one step with respect to
 * the worker's delete-and-bump. Without it a refresh could read "op still pending", the worker
 * could settle it, and the refresh would then delete the row it believed protected.
 */
@Singleton
class CompletionRefreshGuard @Inject constructor() {
    private val lock = Mutex()
    private val epoch = AtomicLong(0)

    /** The epoch a refresh captures BEFORE it sends its request. */
    fun currentEpoch(): Long = epoch.get()

    /**
     * Runs [block], a change to the completions cache (ops settled, rows reconciled), and bumps
     * the epoch with it, atomically with respect to [applyIfUnchanged]. Bumps even when [block]
     * throws: a half-done change is still a change a stale answer must not be applied over.
     */
    suspend fun <T> settling(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } finally {
            epoch.incrementAndGet()
        }
    }

    /** [settling] when [condition] holds, plain [block] otherwise (a batch with no completion in it). */
    suspend fun <T> settlingIf(condition: Boolean, block: suspend () -> T): T =
        if (condition) settling(block) else block()

    /** Runs [apply] only if no settling happened since [captured]; false when it was refused. */
    suspend fun applyIfUnchanged(captured: Long, apply: suspend () -> Unit): Boolean = lock.withLock {
        if (epoch.get() != captured) {
            false
        } else {
            apply()
            true
        }
    }

    /**
     * One refresh: [fetch] (the network, outside the lock), then [apply] (the Room replace) if
     * nothing was settled meanwhile. A stale answer is dropped and fetched once more; null when
     * the second one is stale too (pushes keep landing: the cache is left as it is, and the
     * worker's own redraws bring it up to date). [fetch] throws on failure, [apply] gets its result.
     */
    suspend fun <T : Any> fetchThenApply(fetch: suspend () -> T, apply: suspend (T) -> Unit): T? {
        repeat(MAX_ATTEMPTS) {
            val captured = currentEpoch()
            val fetched = fetch()
            if (applyIfUnchanged(captured) { apply(fetched) }) return fetched
        }
        return null
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

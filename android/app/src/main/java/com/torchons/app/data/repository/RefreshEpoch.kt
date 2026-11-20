package com.torchons.app.data.repository

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One epoch + one short lock guarding one cached scope (completions, tasks) against a refresh
 * whose answer is older than a change the sync worker made. The reasoning, and why the lock only
 * ever wraps LOCAL work, is in [CompletionRefreshGuard] (its first user).
 */
class RefreshEpoch {
    private val lock = Mutex()
    private val epoch = AtomicLong(0)

    /** The epoch a refresh captures BEFORE it sends its request. */
    fun current(): Long = epoch.get()

    /**
     * Runs [block], a change to the cached scope a refresh could not have seen, and bumps the
     * epoch with it, atomically with respect to [applyIfUnchanged]. Bumps even when [block]
     * throws: a half-done change is still a change a stale answer must not be applied over.
     */
    suspend fun <T> settling(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } finally {
            epoch.incrementAndGet()
        }
    }

    /** [settling] when [condition] holds, plain [block] otherwise. */
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
            val captured = current()
            val fetched = fetch()
            if (applyIfUnchanged(captured) { apply(fetched) }) return fetched
        }
        return null
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

package com.torchons.app.data.repository

import com.torchons.app.data.local.AppDatabase
import com.torchons.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sequence of a household change, with its two local steps handed in so that it is testable
 * without Hilt: [changeToken] (makes another household the active one), then, only if it
 * succeeded, [wipe] and [refresh]. Run in [scope] (the application's) behind one lock: leaving the
 * screen never cuts it between the token and the wipe, and two changes never interleave.
 */
internal class HouseholdSwitchRunner(
    private val scope: CoroutineScope,
    private val wipe: suspend () -> Unit,
    private val refresh: suspend () -> Unit,
) {
    private val lock = Mutex()

    suspend fun run(changeToken: suspend () -> Result<Unit>): Result<Unit> =
        scope.async {
            lock.withLock {
                changeToken().mapCatching {
                    wipe()
                    refresh()
                }
            }
        }.await()
}

/**
 * The ONE way the active household changes on this phone: token switch, wipe of every cached
 * table, refresh of the new household's data, as a single sequence run in the application scope
 * (D5, 2026-11-20 audit). It used to be spread over the screens' ViewModel scopes: leaving the
 * screen between the token switch and the wipe left the NEW token with the OLD household's cache;
 * the leave-that-switches never wiped; the wizard wiped only when its import had worked.
 *
 * Callers: the switch sheet, create-and-switch, leave (which receives a new token), the
 * household wizard. They wait for the result, they do not run the sequence.
 *
 * The offline queue is NOT wiped (see [wipeHouseholdCaches]).
 */
@Singleton
class HouseholdSwitcher @Inject constructor(
    private val db: AppDatabase,
    private val tasks: TaskRepository,
    private val delegations: DelegationRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val runner = HouseholdSwitchRunner(scope, wipe = { db.wipeHouseholdCaches() }, refresh = ::refreshAll)

    /**
     * Runs [changeToken] (the call that makes another household the active one), then, if it
     * succeeded, wipes the previous household's cache and refreshes. A failed [changeToken] leaves
     * everything as it was. The sequence survives the caller leaving.
     */
    suspend fun switchWith(changeToken: suspend () -> Result<Unit>): Result<Unit> = runner.run(changeToken)

    /** Re-fetches what the household screens show; best effort, each one on its own. */
    suspend fun refreshAll() {
        runCatching { tasks.refreshTasks() }
        runCatching { tasks.refreshContexts() }
        runCatching { tasks.refreshUsers() }
        runCatching { tasks.refreshCompletions(limit = COMPLETIONS_LIMIT) }
        runCatching { delegations.refresh() }
    }

    private companion object {
        const val COMPLETIONS_LIMIT = 2000
    }
}

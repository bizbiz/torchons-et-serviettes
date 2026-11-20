package com.torchons.app.data.repository

import com.torchons.app.core.sync.opPayload
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import javax.inject.Inject
import javax.inject.Singleton

/** The `/sync` table of the member's own settings (backend `_handle_settings`). */
internal const val USER_SETTINGS_TABLE = "user_settings"

/**
 * Queue-only mutation of one member setting (D7, 2026-11-20 audit: `updateSetting` was a REST call
 * whose failure was only logged, so a toggle flipped offline looked saved and was lost). The value
 * lives on the server; the screen and the DataStore copy are the optimistic local side, and a
 * reload overlays the queued ops on what the server answers ([overlayPendingSettings]).
 *
 * One transaction in the application scope, sync requested after the commit ([LocalMutation]).
 * `/sync` coalesces nothing, ops are applied in order: the last write of a key wins.
 */
internal class SettingsLedger(private val mutation: LocalMutation) : SettingsQueue {
    override suspend fun queueUpdate(userId: String, key: String, value: Any) {
        mutation.run { enqueue(USER_SETTINGS_TABLE, "UPDATE", userId, mapOf(key to value)) }
    }
}

/** What the settings screen needs to queue a member setting; the ledger in the app and in tests. */
internal interface SettingsQueue {
    suspend fun queueUpdate(userId: String, key: String, value: Any)
}

/**
 * [serverData] (the `GET /api/settings` answer) with the queued `user_settings` ops applied on
 * top, oldest first: what the member last chose, even while the server has not heard of it yet.
 * Without it, opening the screen online between the tap and the next sync showed the old value
 * and wrote it back to the DataStore.
 */
internal fun overlayPendingSettings(
    serverData: Map<String, Any?>,
    queue: List<PendingOperationEntity>,
): Map<String, Any?> = queue
    .filter { it.tableName == USER_SETTINGS_TABLE && it.operation == "UPDATE" }
    .sortedBy { it.createdAt }
    .fold(serverData) { acc, op -> opPayload(op)?.let { acc + it } ?: acc }

/** The Hilt-facing entry point for member settings; [com.torchons.app.ui.settings.SettingsViewModel] uses it. */
@Singleton
class SettingsWriter @Inject constructor(
    offline: OfflineWriter,
    private val pendingDao: PendingOperationDao,
) : SettingsQueue {
    private val ledger = SettingsLedger(offline.mutation)

    override suspend fun queueUpdate(userId: String, key: String, value: Any) =
        ledger.queueUpdate(userId, key, value)

    /** [serverData] with the settings still waiting in the queue applied on top. */
    suspend fun withPending(serverData: Map<String, Any?>): Map<String, Any?> =
        overlayPendingSettings(serverData, pendingDao.getAll())
}

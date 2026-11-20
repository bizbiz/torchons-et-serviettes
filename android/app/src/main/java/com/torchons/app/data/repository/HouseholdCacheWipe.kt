package com.torchons.app.data.repository

import androidx.room.withTransaction
import com.torchons.app.data.local.AppDatabase

/** The one table a household switch keeps: the offline queue, whose ops carry their own `foyerId`. */
internal const val QUEUE_TABLE = "pending_operations"

/**
 * Empties EVERY cached table except [QUEUE_TABLE], in one transaction (D5, 2026-11-20 audit: the
 * old wipe listed four DAOs and left delegations, view snapshots and task history of the previous
 * household on screen). The table list is read from SQLite, so a table added later is wiped too
 * without anyone remembering to add it here. Room's own bookkeeping (`room_*`, `android_*`,
 * `sqlite_*`) is left alone.
 *
 * Why the queue stays: an op queued in household A is sent with its `foyer_id`, and the server
 * applies it in A as long as the user is still a member (otherwise it answers `out_of_scope`, and
 * the worker drops it, see SyncConflicts.kt). Wiping it would lose work that was never sent.
 */
internal suspend fun AppDatabase.wipeHouseholdCaches() {
    withTransaction {
        val sqlite = openHelper.writableDatabase
        val tables = buildList {
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' AND name != '$QUEUE_TABLE'",
            ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        tables.forEach { sqlite.execSQL("DELETE FROM `$it`") }
    }
}

package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

data class SyncOperation(
    @SerializedName("client_op_id") val clientOpId: String,
    @SerializedName("table") val table: String,
    @SerializedName("operation") val operation: String,
    @SerializedName("row_id") val rowId: String,
    @SerializedName("data") val data: Map<String, Any?>? = null,
    @SerializedName("client_timestamp") val clientTimestamp: String,
    /** Household the op was queued in; omitted when null (older queued ops). */
    @SerializedName("foyer_id") val foyerId: String? = null,
)

data class SyncRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("last_server_version") val lastServerVersion: Long = 0,
    @SerializedName("changes") val changes: List<SyncOperation> = emptyList(),
)

data class SyncAppliedOp(
    @SerializedName("client_op_id") val clientOpId: String,
    @SerializedName("server_row_id") val serverRowId: String,
    @SerializedName("new_version") val newVersion: Long,
    /**
     * True when the server acknowledged the op without writing anything (a duplicate completion).
     * Absent on older servers: false, behaviour unchanged.
     */
    @SerializedName("dropped") val dropped: Boolean = false,
)

data class SyncConflict(
    @SerializedName("client_op_id") val clientOpId: String,
    @SerializedName("table") val table: String,
    @SerializedName("row_id") val rowId: String,
    @SerializedName("server_data") val serverData: Map<String, Any?>? = null,
    @SerializedName("reason") val reason: String,
    /** Why the op failed, for `op_error` (sync.py); the dialog shows it. */
    @SerializedName("detail") val detail: String? = null,
)

data class ChangeLogEntry(
    @SerializedName("server_version") val serverVersion: Long,
    @SerializedName("table") val table: String,
    @SerializedName("row_id") val rowId: String,
    @SerializedName("operation") val operation: String,
    @SerializedName("data") val data: Map<String, Any?>? = null,
    @SerializedName("changed_at") val changedAt: String,
)

data class SyncResponse(
    @SerializedName("server_version") val serverVersion: Long,
    @SerializedName("applied") val applied: List<SyncAppliedOp> = emptyList(),
    @SerializedName("conflicts") val conflicts: List<SyncConflict> = emptyList(),
    @SerializedName("server_changes") val serverChanges: List<ChangeLogEntry> = emptyList(),
    /** True when the server cut this pull at its row limit: ask again from `server_version`. */
    @SerializedName("has_more") val hasMore: Boolean = false,
)

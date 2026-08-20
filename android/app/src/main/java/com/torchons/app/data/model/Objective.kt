package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * DTOs des endpoints /api/objectives (v0.31, item roadmap "Objectifs
 * personnels et partages").
 */

data class ObjectiveCreateRequest(
    @SerializedName("title") val title: String,
    @SerializedName("kind") val kind: String = "shared",  // 'personal' | 'shared'
    @SerializedName("period") val period: String = "week", // 'day'|'week'|'month'
    @SerializedName("target_count") val targetCount: Int = 1,
    @SerializedName("scope_context_id") val scopeContextId: String? = null,
    @SerializedName("assigned_to_user_id") val assignedToUserId: String? = null,
)

data class Objective(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("period") val period: String,
    @SerializedName("target_count") val targetCount: Int,
    @SerializedName("scope_context_id") val scopeContextId: String? = null,
    @SerializedName("assigned_to_user_id") val assignedToUserId: String? = null,
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("progress_count") val progressCount: Int = 0,
    @SerializedName("progress_percent") val progressPercent: Int = 0,
    @SerializedName("created_at") val createdAt: String,
)

package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

data class UserStats(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("points_week") val pointsWeek: Int = 0,
    @SerializedName("points_month") val pointsMonth: Int = 0,
    @SerializedName("points_total") val pointsTotal: Int = 0,
    @SerializedName("tasks_week") val tasksWeek: Int = 0,
    @SerializedName("tasks_month") val tasksMonth: Int = 0,
    @SerializedName("tasks_total") val tasksTotal: Int = 0
)

data class LeaderboardEntry(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("points") val points: Int = 0,
    @SerializedName("normalized_score") val normalizedScore: Double = 0.0,
    @SerializedName("tasks_count") val tasksCount: Int = 0,
    @SerializedName("rank") val rank: Int = 0
)

data class StreakInfo(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("current_streak") val currentStreak: Int = 0,
    @SerializedName("best_streak") val bestStreak: Int = 0
)

data class BadgeInfo(
    @SerializedName("badge_type") val badgeType: String,
    @SerializedName("label") val label: String,
    @SerializedName("description") val description: String,
    @SerializedName("earned_at") val earnedAt: String? = null,
    @SerializedName("earned") val earned: Boolean = false
)

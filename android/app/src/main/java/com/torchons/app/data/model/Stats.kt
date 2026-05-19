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

// --- Stats long-terme (v0.31, item roadmap "Stats long-terme et par dimension") ---

data class LongTermDay(
    /** ISO date YYYY-MM-DD. */
    @SerializedName("day") val day: String,
    @SerializedName("completions") val completions: Int,
    @SerializedName("points") val points: Int,
    @SerializedName("estimated_minutes") val estimatedMinutes: Int,
)

data class LongTermRoom(
    @SerializedName("room_id") val roomId: String?,
    @SerializedName("room_name") val roomName: String,
    @SerializedName("completions") val completions: Int,
    @SerializedName("points") val points: Int,
    @SerializedName("estimated_minutes") val estimatedMinutes: Int,
)

data class LongTermResponse(
    @SerializedName("period") val period: String, // 'month' | 'quarter' | 'year'
    @SerializedName("days") val days: Int,
    @SerializedName("completions_per_day") val completionsPerDay: List<LongTermDay>,
    @SerializedName("by_room") val byRoom: List<LongTermRoom>,
)

// --- Totaux du couple (v0.31, item roadmap "Stats globales du couple") ---

data class CoupleTotalsResponse(
    @SerializedName("foyer_total_completions") val foyerTotalCompletions: Int,
    @SerializedName("foyer_total_points") val foyerTotalPoints: Int,
    @SerializedName("foyer_month_completions") val foyerMonthCompletions: Int,
    @SerializedName("foyer_month_points") val foyerMonthPoints: Int,
    @SerializedName("foyer_week_completions") val foyerWeekCompletions: Int,
    @SerializedName("foyer_week_points") val foyerWeekPoints: Int,
    @SerializedName("couple_streak_days") val coupleStreakDays: Int,
)

// --- Resume hebdomadaire (v0.31, item roadmap "Statistiques hebdomadaires") ---

data class WeeklySummaryUser(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("completions_count") val completionsCount: Int,
    @SerializedName("points_earned") val pointsEarned: Int,
)

data class WeeklySummaryTopTask(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("count") val count: Int,
)

data class WeeklySummaryResponse(
    /** Lundi de la semaine, ISO date (YYYY-MM-DD). */
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("week_end") val weekEnd: String,
    @SerializedName("week_offset") val weekOffset: Int,
    @SerializedName("per_user") val perUser: List<WeeklySummaryUser>,
    @SerializedName("top_tasks") val topTasks: List<WeeklySummaryTopTask>,
    @SerializedName("foyer_total_completions") val foyerTotalCompletions: Int,
    @SerializedName("foyer_total_points") val foyerTotalPoints: Int,
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
    @SerializedName("best_streak") val bestStreak: Int = 0,
    // P4b sweep 0.34.1 — dates ISO des deux runs (jours actifs + jours
    // absents pontés) pour le liseré. Source unique = serveur /streaks.
    @SerializedName("current_run") val currentRun: List<String> = emptyList(),
    @SerializedName("best_run") val bestRun: List<String> = emptyList()
)

data class BadgeInfo(
    @SerializedName("badge_type") val badgeType: String,
    @SerializedName("label") val label: String,
    @SerializedName("description") val description: String,
    @SerializedName("earned_at") val earnedAt: String? = null,
    @SerializedName("earned") val earned: Boolean = false
)

// --- Graphiques ---

/** Un point de la courbe de cumul (date ISO + total cumule a cette date). */
data class PointsTimelinePoint(
    @SerializedName("date") val date: String,
    @SerializedName("cumulative") val cumulative: Int = 0,
)

data class PointsTimelineUser(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("points") val points: List<PointsTimelinePoint> = emptyList(),
    /** Total brut sur la periode precedente symetrique (W-1, M-1). null pour all. */
    @SerializedName("points_previous") val pointsPrevious: Int? = null,
)

data class PointsTimelineResponse(
    @SerializedName("period") val period: String,        // week | month | all
    @SerializedName("bucket") val bucket: String,        // day | week | month
    @SerializedName("users") val users: List<PointsTimelineUser> = emptyList(),
)

/** Frequence par jour de semaine (index 0=lundi..6=dimanche). */
data class WeekdayFrequencyUser(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("counts") val counts: List<Int> = emptyList(),
    @SerializedName("points") val points: List<Int> = emptyList(),
)

data class WeekdayFrequencyResponse(
    @SerializedName("users") val users: List<WeekdayFrequencyUser> = emptyList(),
)

/** Snapshot quotidien du retard du foyer (taches en commun uniquement). */
data class RetardTimelinePoint(
    @SerializedName("date") val date: String,
    @SerializedName("minutes") val minutes: Int = 0,
    @SerializedName("task_count") val taskCount: Int = 0,
)

data class RetardTimelineResponse(
    @SerializedName("points") val points: List<RetardTimelinePoint> = emptyList(),
)

/** Frequence par categorie : top N categories avec breakdown per-user. */
data class CategoryFrequencyEntry(
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("counts") val counts: List<Int> = emptyList(),
    @SerializedName("total") val total: Int = 0,
)

data class CategoryFrequencyUser(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
)

data class CategoryFrequencyResponse(
    @SerializedName("users") val users: List<CategoryFrequencyUser> = emptyList(),
    @SerializedName("categories") val categories: List<CategoryFrequencyEntry> = emptyList(),
)

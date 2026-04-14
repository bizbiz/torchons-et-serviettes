package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modeles pour les endpoints projection (v0.8.0 magic buttons).
 *
 * Endpoints concernes :
 * - GET /api/retard : retard courant du foyer (minutes + liste tasks overdue)
 * - GET /api/pace : rythme 14j de chaque user du foyer + done today
 * - GET /api/maintenance-rate : min/j combine pour maintenir zero retard
 * - GET /api/objective : objectif perso + unite d'affichage
 * - PUT /api/objective : set objectif perso
 */

@Immutable
data class RetardTask(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("minutes") val minutes: Int,
    @SerializedName("overdue_since") val overdueSince: String? = null,
)

@Immutable
data class RetardResponse(
    @SerializedName("total_minutes") val totalMinutes: Int,
    @SerializedName("task_count") val taskCount: Int,
    @SerializedName("tasks") val tasks: List<RetardTask> = emptyList(),
)

@Immutable
data class UserPace(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("minutes_per_day_avg") val minutesPerDayAvg: Double,
    @SerializedName("tasks_per_day_avg") val tasksPerDayAvg: Double,
    @SerializedName("minutes_today") val minutesToday: Int,
    @SerializedName("tasks_today") val tasksToday: Int,
    @SerializedName("daily_objective_minutes") val dailyObjectiveMinutes: Int? = null,
    @SerializedName("objective_display_unit") val objectiveDisplayUnit: String = "min",
)

@Immutable
data class PaceResponse(
    @SerializedName("window_days") val windowDays: Int,
    @SerializedName("users") val users: List<UserPace> = emptyList(),
)

@Immutable
data class MaintenanceRateResponse(
    @SerializedName("minutes_per_day") val minutesPerDay: Double,
    @SerializedName("task_count_contributing") val taskCountContributing: Int,
)

@Immutable
data class ObjectiveResponse(
    @SerializedName("daily_objective_minutes") val dailyObjectiveMinutes: Int? = null,
    @SerializedName("display_unit") val displayUnit: String = "min",
)

/**
 * Corps du PUT /api/objective. Champs null = pas de modification.
 * daily_objective_minutes peut etre explicitement null (ecrase la valeur existante).
 */
data class ObjectiveUpdate(
    @SerializedName("daily_objective_minutes") val dailyObjectiveMinutes: Int? = null,
    @SerializedName("display_unit") val displayUnit: String? = null,
)

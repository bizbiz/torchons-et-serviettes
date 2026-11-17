package com.torchons.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.UserPace
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * 2 cards cote a cote en haut du HomeScreen (v0.8.0 magic buttons Feature 3).
 *
 * - Card "Moi" : quota quotidien perso (min/tache faites aujourd'hui,
 *   progress bar vers l'objectif, jours restants pour rattraper le retard
 *   a ce rythme).
 * - Card "Foyer" : retard actuel du foyer, rythme combine, date
 *   previsionnelle de rattrapage, maintien post-rattrapage.
 *
 * Tap "Moi" -> popup simulation (ProjectionSimulationDialog, outil objectif
 * perso, inchange). Tap "Foyer" -> navigue vers l'ecran Foyer (P1 refonte,
 * design decision 7).
 */

@Composable
fun ProjectionCardsRow(
    pace: PaceResponse?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    currentUserId: String?,
    onMoiClick: () -> Unit,
    onFoyerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val me = pace?.users?.find { it.userId == currentUserId }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardMoi(
            me = me,
            retard = retard,
            maintenance = maintenance,
            onClick = onMoiClick,
            modifier = Modifier.weight(1f),
        )
        CardFoyer(
            pace = pace,
            retard = retard,
            maintenance = maintenance,
            onClick = onFoyerClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CardMoi(
    me: UserPace?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = me?.objectiveDisplayUnit ?: "min"
    val objectiveMin = me?.dailyObjectiveMinutes
        ?: me?.minutesPerDayAvg?.toInt()?.coerceAtLeast(1)
        ?: 0
    val doneMin = me?.minutesToday ?: 0
    val doneTasks = me?.tasksToday ?: 0
    val progress = if (objectiveMin > 0) (doneMin.toFloat() / objectiveMin).coerceIn(0f, 1f) else 0f
    val quotaReached = doneMin >= objectiveMin && objectiveMin > 0
    val remainingMin = (objectiveMin - doneMin).coerceAtLeast(0)
    val doneTxt = if (unit == "tasks")
        stringResource(R.string.projection_card_unit_tasks_format, doneTasks)
    else stringResource(R.string.projection_card_unit_min_format, doneMin)
    val objectiveTxt = if (unit == "tasks") {
        val tasks = (objectiveMin / 5).coerceAtLeast(1)
        stringResource(R.string.projection_card_unit_tasks_format, tasks)
    } else stringResource(R.string.projection_card_unit_min_format, objectiveMin)

    // Nombre de jours a l'objectif pour rattraper le retard (en supposant
    // le reste du foyer constant)
    val daysCatchupAtObjective = remainingDaysAtRate(
        retardMinutes = retard?.totalMinutes ?: 0,
        myRate = objectiveMin.toDouble(),
        maintenancePerDay = maintenance?.minutesPerDay ?: 0.0,
    )

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.projection_card_self_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (quotaReached) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(TaskUrgencyCalculator.VERT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.projection_card_quota_reached_cd),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.projection_card_progress_format, doneTxt, objectiveTxt),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (quotaReached) TaskUrgencyCalculator.VERT else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (quotaReached) stringResource(R.string.projection_card_objective_reached)
                else stringResource(
                    R.string.projection_card_remaining_format,
                    if (unit == "tasks")
                        stringResource(R.string.projection_card_unit_tasks_format, (remainingMin / 5).coerceAtLeast(1))
                    else stringResource(R.string.projection_card_unit_min_format, remainingMin),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (daysCatchupAtObjective != null && daysCatchupAtObjective > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.projection_card_catchup_format, daysCatchupAtObjective),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardFoyer(
    pace: PaceResponse?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retardMin = retard?.totalMinutes ?: 0
    val retardTasks = retard?.taskCount ?: 0
    val foyerRate = pace?.users?.sumOf { it.minutesPerDayAvg } ?: 0.0
    val maintPerDay = maintenance?.minutesPerDay ?: 0.0
    val daysToCatchUp = remainingDaysAtRate(retardMin, foyerRate, maintPerDay)
    val catchupDate = daysToCatchUp?.let {
        if (it <= 0) null else LocalDate.now().plusDays(it.toLong())
    }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.projection_card_foyer_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (retardMin == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = TaskUrgencyCalculator.VERT,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.projection_card_zero_retard),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TaskUrgencyCalculator.VERT,
                    )
                }
                if (maintPerDay > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.projection_card_maintenance_format, maintPerDay.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = formatMinutes(retardMin),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TaskUrgencyCalculator.ROUGE,
                )
                Text(
                    text = stringResource(R.string.projection_card_retard_tasks_format, retardTasks),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (daysToCatchUp == null) {
                    Text(
                        text = stringResource(R.string.projection_card_rate_insufficient),
                        style = MaterialTheme.typography.labelSmall,
                        color = TaskUrgencyCalculator.ROUGE,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                R.string.projection_card_catchup_date_format,
                                daysToCatchUp,
                                catchupDate?.format(catchupDateFormatter()) ?: "-",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (maintPerDay > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.projection_card_maintenance_format, maintPerDay.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Nombre de jours pour rattraper `retardMinutes` au rythme `rate` min/j
 * sachant que pendant ce temps le backlog grandit au rythme de maintenance.
 *
 * Formule : days = retard / (rate - maintenance)
 * Retourne null si rate <= maintenance (jamais rattrape) ou si retard = 0.
 */
private fun remainingDaysAtRate(
    retardMinutes: Int,
    myRate: Double,
    maintenancePerDay: Double,
): Int? {
    if (retardMinutes <= 0) return 0
    val netRate = myRate - maintenancePerDay
    if (netRate <= 0) return null
    return ceil(retardMinutes / netRate).toInt()
}

@Composable
private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return stringResource(R.string.projection_card_minutes_format, minutes)
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) stringResource(R.string.projection_card_hours_format, h)
    else stringResource(R.string.projection_card_hours_minutes_format, h, m)
}

/** « 3 Dec » in the phone's language (was forced to French, « 3 déc. » on an English phone). */
@Composable
private fun catchupDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return androidx.compose.runtime.remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
}

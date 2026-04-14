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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.UserPace
import com.torchons.app.util.FreshnessCalculator
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
 * Tap sur l'une ou l'autre -> popup simulation (ProjectionSimulationDialog).
 */

@Composable
fun ProjectionCardsRow(
    pace: PaceResponse?,
    retard: RetardResponse?,
    maintenance: MaintenanceRateResponse?,
    currentUserId: String?,
    onCardClick: () -> Unit,
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
            onClick = onCardClick,
            modifier = Modifier.weight(1f),
        )
        CardFoyer(
            pace = pace,
            retard = retard,
            maintenance = maintenance,
            onClick = onCardClick,
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
    val doneTxt = if (unit == "tasks") "$doneTasks t." else "$doneMin min"
    val objectiveTxt = if (unit == "tasks") {
        val tasks = (objectiveMin / 5).coerceAtLeast(1)
        "$tasks t."
    } else "$objectiveMin min"

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
                    text = "Moi",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (quotaReached) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(FreshnessCalculator.VERT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Quota atteint",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$doneTxt / $objectiveTxt",
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
                color = if (quotaReached) FreshnessCalculator.VERT else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (quotaReached) "Bravo, objectif atteint !"
                else "Encore ${if (unit == "tasks") "${(remainingMin / 5).coerceAtLeast(1)} t." else "$remainingMin min"}",
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
                        text = "Rattrapage : $daysCatchupAtObjective j",
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
                text = "Foyer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (retardMin == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = FreshnessCalculator.VERT,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Zero retard",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = FreshnessCalculator.VERT,
                    )
                }
                if (maintPerDay > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Maintien : ${maintPerDay.toInt()} min/j",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = formatMinutes(retardMin),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = FreshnessCalculator.ROUGE,
                )
                Text(
                    text = "$retardTasks t. en retard",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (daysToCatchUp == null) {
                    Text(
                        text = "Rythme insuffisant",
                        style = MaterialTheme.typography.labelSmall,
                        color = FreshnessCalculator.ROUGE,
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
                            text = "$daysToCatchUp j (${catchupDate?.format(DATE_FORMATTER) ?: "-"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (maintPerDay > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Maintien : ${maintPerDay.toInt()} min/j",
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

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h$m"
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

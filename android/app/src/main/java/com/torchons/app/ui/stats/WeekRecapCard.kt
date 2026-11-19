package com.torchons.app.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torchons.app.R
import com.torchons.app.data.model.CategoryFrequencyResponse
import com.torchons.app.data.model.PointsTimelineResponse
import com.torchons.app.data.model.WeekdayFrequencyResponse

/**
 * Recap "Cette semaine" pour le user courant : ses points de la semaine + delta
 * vs sem precedente, top jour de semaine et top categorie. Carte legere qui
 * vit en haut du Resume et reuse les donnees deja chargees (pas de query
 * supplementaire au-dela des 2 fetches "this week" du ViewModel).
 */
@Composable
internal fun WeekRecapCard(
    pointsWeek: PointsTimelineResponse?,
    weekdayThisWeek: WeekdayFrequencyResponse?,
    categoryThisWeek: CategoryFrequencyResponse?,
    currentUserId: String?,
) {
    // Trouver le user courant dans pointsWeek pour son delta personnel.
    val me = pointsWeek?.users?.firstOrNull { it.userId == currentUserId }
    val myCurrent = me?.points?.lastOrNull()?.cumulative ?: 0
    val myPrevious = me?.pointsPrevious

    // Top weekday : on aggrege tous les users (foyer-level pour la lecture
    // rapide), prend le max. Indice 0=lundi..6=dimanche.
    val weekdaySum = IntArray(7)
    weekdayThisWeek?.users?.forEach { u ->
        u.counts.take(7).forEachIndexed { i, c -> weekdaySum[i] += c }
    }
    val topWeekdayIdx = if (weekdaySum.any { it > 0 })
        weekdaySum.indices.maxBy { weekdaySum[it] } else null
    val weekdayLabels = listOf(
        stringResource(R.string.weekday_monday),
        stringResource(R.string.weekday_tuesday),
        stringResource(R.string.weekday_wednesday),
        stringResource(R.string.weekday_thursday),
        stringResource(R.string.weekday_friday),
        stringResource(R.string.weekday_saturday),
        stringResource(R.string.weekday_sunday),
    )

    // Top categorie cette semaine : la 1ere de la liste (deja triee desc backend).
    val topCategory = categoryThisWeek?.categories?.firstOrNull { it.total > 0 }

    // Si rien a montrer, on n'affiche pas la carte (foyer fraichement cree
    // ou debut de semaine encore vide).
    val nothingToShow = me == null && topWeekdayIdx == null && topCategory == null
    if (nothingToShow) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_weekrecap_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            // Ligne 1 : mes points + delta. Mise en valeur visuelle.
            if (me != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "$myCurrent",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.stats_weekrecap_points_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (myPrevious != null) {
                        Spacer(Modifier.width(12.dp))
                        val deltaText = formatWeekDelta(myCurrent, myPrevious)
                        val deltaColor = when {
                            myPrevious == 0 && myCurrent == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            myPrevious == 0 -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            myCurrent > myPrevious -> androidx.compose.ui.graphics.Color(0xFF10B981)
                            myCurrent < myPrevious -> androidx.compose.ui.graphics.Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = deltaColor,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Ligne 2 : top jour + top categorie cote foyer (lecture rapide).
            if (topWeekdayIdx != null) {
                val nbTop = weekdaySum[topWeekdayIdx]
                val countLabel = pluralStringResource(R.plurals.stats_top_day_count, nbTop, nbTop)
                Text(
                    text = "${stringResource(R.string.stats_weekrecap_top_day_format, weekdayLabels[topWeekdayIdx])} $countLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (topCategory != null) {
                val countLabel = pluralStringResource(R.plurals.stats_top_category_count, topCategory.total, topCategory.total)
                Text(
                    text = "${stringResource(R.string.stats_weekrecap_top_category_format, topCategory.categoryName)} $countLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Formate "+15% vs sem prec." / "-20%" / "= vs sem prec." pour le recap. */
@Composable
internal fun formatWeekDelta(current: Int, previous: Int): String {
    if (previous == 0 && current == 0) return stringResource(R.string.stats_weekrecap_delta_same)
    if (previous == 0) return stringResource(R.string.stats_weekrecap_delta_zero_to_some)
    val pct = ((current - previous).toDouble() / previous.toDouble() * 100.0).toInt()
    return when {
        pct == 0 -> stringResource(R.string.stats_weekrecap_delta_same)
        pct > 0 -> stringResource(R.string.stats_weekrecap_delta_positive_format, pct)
        else -> stringResource(R.string.stats_weekrecap_delta_negative_format, pct)
    }
}

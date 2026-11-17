package com.torchons.app.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.input.pointer.pointerInput
import com.torchons.app.R
import com.torchons.app.data.model.CategoryFrequencyResponse
import com.torchons.app.data.model.PointsTimelineResponse
import com.torchons.app.data.model.RetardTimelineResponse
import com.torchons.app.data.model.WeekdayFrequencyResponse
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Retard global du foyer dans le temps. Single-line + zone remplie sous la
 * courbe (foyer-level, pas de multi-series). Toggle Minutes / Taches.
 *
 * Granularite : journaliere depuis la 1ere creation de tache du foyer (la
 * resolution backend, on ne re-buckette pas cote client).
 */
@Composable
fun RetardTimelineChart(
    data: RetardTimelineResponse,
    modifier: Modifier = Modifier,
    period: RetardPeriod? = null,
    onPeriodChange: ((RetardPeriod) -> Unit)? = null,
    isLoading: Boolean = false,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
    )
    val chartEmptyMsg = stringResource(R.string.chart_empty)
    // A series that exists but stays at zero is good news, not missing data: saying
    // "no data yet" there read as a contradiction next to the live counter (todo, 12/11).
    val chartNoneMsg = stringResource(R.string.chart_retard_none)
    // Couleur "alerte" pour le retard — code couleur retard.rouge sur la
    // home, mais en variation un peu plus violette pour ne pas confondre
    // avec les "tasks rouges en retard" du jour.
    val retardColor = Color(0xFFEF4444)
    val retardFill = retardColor.copy(alpha = 0.18f)

    var showMinutes by remember { mutableStateOf(true) }

    val points = data.points
    val series: List<Int> = if (showMinutes) points.map { it.minutes } else points.map { it.taskCount }
    val maxY = series.maxOrNull() ?: 0

    Column(modifier = modifier) {
        // 1) Chips de periode (Tout / 1 mois) si callback fourni.
        if (period != null && onPeriodChange != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilterChip(
                    selected = period == RetardPeriod.ALL,
                    onClick = { if (period != RetardPeriod.ALL) onPeriodChange(RetardPeriod.ALL) },
                    label = { Text(stringResource(R.string.chart_retard_period_all)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = period == RetardPeriod.MONTH,
                    onClick = { if (period != RetardPeriod.MONTH) onPeriodChange(RetardPeriod.MONTH) },
                    label = { Text(stringResource(R.string.chart_retard_period_month)) },
                )
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
        }

        // 2) Toggle Minutes / Taches (chips Material3).
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showMinutes,
                onClick = { showMinutes = true },
                label = { Text(stringResource(R.string.chart_retard_toggle_minutes)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !showMinutes,
                onClick = { showMinutes = false },
                label = { Text(stringResource(R.string.chart_retard_toggle_tasks)) },
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (points.size < 2) {
                drawCenterMessage(measurer, chartEmptyMsg, labelStyle)
                return@Canvas
            }
            if (maxY <= 0) {
                drawCenterMessage(measurer, chartNoneMsg, labelStyle)
                return@Canvas
            }

            val leftPad = with(density) { 44.dp.toPx() }
            val bottomPad = with(density) { 22.dp.toPx() }
            val topPad = with(density) { 8.dp.toPx() }
            val rightPad = with(density) { 8.dp.toPx() }
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad

            // Echelle Y arrondie au "joli" multiple superieur.
            val yMaxNice = niceCeil(maxY, 4)

            val yTicks = 4
            for (i in 0..yTicks) {
                val frac = i.toFloat() / yTicks
                val y = topPad + plotH * (1f - frac)
                drawLine(
                    color = if (i == 0) axisColor else gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(size.width - rightPad, y),
                    strokeWidth = if (i == 0) 1.5f else 1f,
                )
                val v = (yMaxNice * frac).toInt()
                val lab = if (showMinutes) formatMinutes(v) else v.toString()
                val txt = measurer.measure(lab, labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(leftPad - txt.size.width - 4f, y - txt.size.height / 2f),
                )
            }

            // Axe X : labels date (5 max).
            val xLabels = pickAxisLabels(points.map { it.date }, "day", maxLabels = 5)
            for ((idx, label) in xLabels) {
                val x = leftPad + plotW * (idx.toFloat() / (points.size - 1).toFloat())
                drawLine(
                    color = axisColor,
                    start = Offset(x, topPad + plotH),
                    end = Offset(x, topPad + plotH + 4f),
                    strokeWidth = 1f,
                )
                val txt = measurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(
                        (x - txt.size.width / 2f).coerceIn(0f, size.width - txt.size.width),
                        topPad + plotH + 6f,
                    ),
                )
            }

            // Zone remplie sous la courbe (closed path : x0 bottom -> points -> xN bottom).
            val areaPath = Path()
            val linePath = Path()
            val baseY = topPad + plotH
            series.forEachIndexed { j, v ->
                val x = leftPad + plotW * (j.toFloat() / (points.size - 1).toFloat())
                val y = topPad + plotH * (1f - v.toFloat() / yMaxNice.toFloat())
                if (j == 0) {
                    areaPath.moveTo(x, baseY)
                    areaPath.lineTo(x, y)
                    linePath.moveTo(x, y)
                } else {
                    areaPath.lineTo(x, y)
                    linePath.lineTo(x, y)
                }
                if (j == series.size - 1) {
                    areaPath.lineTo(x, baseY)
                    areaPath.close()
                }
            }
            drawPath(path = areaPath, color = retardFill)
            drawPath(
                path = linePath,
                color = retardColor,
                style = Stroke(width = with(density) { 2.5.dp.toPx() }),
            )
            // Marqueur au dernier point pour matcher la valeur "actuelle".
            val lastIdx = series.size - 1
            val lastX = leftPad + plotW * (lastIdx.toFloat() / (points.size - 1).toFloat())
            val lastY = topPad + plotH * (1f - series[lastIdx].toFloat() / yMaxNice.toFloat())
            drawCircle(
                color = retardColor,
                radius = with(density) { 4.dp.toPx() },
                center = Offset(lastX, lastY),
            )
        }

        // Etat actuel : sous-titre lisible.
        Spacer(Modifier.height(8.dp))
        val current = points.lastOrNull()
        val firstNonZero = points.firstOrNull { it.minutes > 0 || it.taskCount > 0 }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (showMinutes) formatMinutes(current?.minutes ?: 0)
                           else "${current?.taskCount ?: 0}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = retardColor,
                )
                Text(
                    text = stringResource(R.string.chart_retard_today),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (firstNonZero != null && current != null && firstNonZero.date != current.date) {
                val deltaMin = (current.minutes) - (firstNonZero.minutes)
                val deltaTask = (current.taskCount) - (firstNonZero.taskCount)
                val delta = if (showMinutes) deltaMin else deltaTask
                val formatted = if (showMinutes) {
                    (if (delta >= 0) "+" else "−") + formatMinutes(kotlin.math.abs(delta))
                } else {
                    (if (delta >= 0) "+" else "") + delta.toString()
                }
                // Vert si retard a baisse (delta negatif), rouge sinon.
                val deltaColor = if (delta < 0) Color(0xFF10B981) else retardColor
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = deltaColor,
                    )
                    Text(
                        text = stringResource(R.string.chart_retard_vs_start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Formate une duree en minutes en "Xh YYmin" / "Xh" / "Ymin". */
private fun formatMinutes(min: Int): String {
    if (min < 60) return "${min}min"
    val h = min / 60
    val m = min % 60
    return if (m == 0) "${h}h" else "${h}h${m.toString().padStart(2, '0')}"
}

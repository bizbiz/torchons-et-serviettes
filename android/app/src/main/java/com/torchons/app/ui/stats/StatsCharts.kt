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

/** Palette stable pour assigner une couleur a chaque user (jusqu'a 4 users). */
private val ChartPalette = listOf(
    Color(0xFF6366F1),  // Indigo (primary)
    Color(0xFFEC4899),  // Rose (tertiary)
    Color(0xFF10B981),  // Vert (freshness)
    Color(0xFFF59E0B),  // Jaune
)

private fun colorFor(index: Int): Color = ChartPalette[index % ChartPalette.size]

/** Legende horizontale : pastille colore + nom de l'user, separes par espace. */
@Composable
fun ChartLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { i, (name, color) ->
            if (i > 0) Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Course de points : courbes cumulees superposees, une par user.
 * Axe X : temps (date). Axe Y : points cumules. Echelle Y commune (max global).
 *
 * `bucket` n'est utilise que pour le formatage des labels d'axe :
 *  - "day"  : jj/MM
 *  - "week" : "S" + numero de semaine ISO
 */
@Composable
fun PointsTimelineChart(
    data: PointsTimelineResponse,
    modifier: Modifier = Modifier,
    onPointTap: ((isoDate: String) -> Unit)? = null,
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

    // Filtrer les users sans aucun point
    val users = data.users.filter { it.points.isNotEmpty() }
    val maxY = users.flatMap { it.points }.maxOfOrNull { it.cumulative } ?: 0
    val xCount = users.firstOrNull()?.points?.size ?: 0
    val refDates = users.firstOrNull()?.points?.map { it.date } ?: emptyList()

    // Padding doit etre coherent entre le draw et le tap mapping. On les
    // calcule une fois en .dp.
    val leftPadDp = 36.dp
    val rightPadDp = 8.dp

    Column(modifier = modifier) {
        ChartLegend(
            entries = users.mapIndexed { i, u -> u.displayName to colorFor(i) },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Modifier de tap : si onPointTap fourni et series non vide, mappe
        // l'offset X au bucket le plus proche puis remonte la date ISO.
        val canvasMod = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 4.dp)
            .let { base ->
                if (onPointTap != null && xCount >= 2 && refDates.isNotEmpty()) {
                    base.pointerInput(refDates) {
                        detectTapGestures { offset ->
                            val leftPx = with(density) { leftPadDp.toPx() }
                            val rightPx = with(density) { rightPadDp.toPx() }
                            val plotW = size.width.toFloat() - leftPx - rightPx
                            if (plotW <= 0f) return@detectTapGestures
                            val rel = ((offset.x - leftPx) / plotW).coerceIn(0f, 1f)
                            val idx = (rel * (xCount - 1)).roundToInt()
                                .coerceIn(0, refDates.size - 1)
                            onPointTap(refDates[idx])
                        }
                    }
                } else base
            }

        Canvas(modifier = canvasMod) {
            if (users.isEmpty() || xCount < 2 || maxY <= 0) {
                drawCenterMessage(measurer, chartEmptyMsg, labelStyle)
                return@Canvas
            }

            val leftPad = with(density) { 36.dp.toPx() }
            val bottomPad = with(density) { 22.dp.toPx() }
            val topPad = with(density) { 8.dp.toPx() }
            val rightPad = with(density) { 8.dp.toPx() }
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad

            // --- Axe Y : 4 lignes horizontales (0, 25%, 50%, 75%, 100%) ---
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
                val value = (maxY * frac).toInt()
                val txt = measurer.measure(value.toString(), labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(leftPad - txt.size.width - 4f, y - txt.size.height / 2f),
                )
            }

            // --- Axe X : ticks + labels (max 5 labels) ---
            val xLabels = pickAxisLabels(data.users.firstOrNull()?.points?.map { it.date } ?: emptyList(), data.bucket, maxLabels = 5)
            for ((idx, label) in xLabels) {
                val x = leftPad + plotW * (idx.toFloat() / (xCount - 1).toFloat())
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

            // --- Courbes ---
            users.forEachIndexed { i, user ->
                val color = colorFor(i)
                val path = Path()
                user.points.forEachIndexed { j, p ->
                    val x = leftPad + plotW * (j.toFloat() / (xCount - 1).toFloat())
                    val y = topPad + plotH * (1f - p.cumulative.toFloat() / maxY.toFloat())
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = with(density) { 2.5.dp.toPx() }),
                )
                // Points marqueurs
                user.points.forEachIndexed { j, p ->
                    val x = leftPad + plotW * (j.toFloat() / (xCount - 1).toFloat())
                    val y = topPad + plotH * (1f - p.cumulative.toFloat() / maxY.toFloat())
                    drawCircle(
                        color = color,
                        radius = with(density) { 3.dp.toPx() },
                        center = Offset(x, y),
                    )
                }
            }
        }

        // Valeurs finales par user (lecture rapide) + delta vs periode prec.
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            users.forEachIndexed { i, u ->
                val total = u.points.lastOrNull()?.cumulative ?: 0
                val previous = u.pointsPrevious
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.chart_points_format, total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorFor(i),
                    )
                    Text(
                        text = u.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (previous != null) {
                        val deltaText = formatDeltaPct(total, previous)
                        // Vert si on progresse vs avant, rouge si on regresse,
                        // gris si stable / pas de baseline.
                        val deltaColor = when {
                            previous == 0 && total == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            previous == 0 -> Color(0xFF10B981)
                            total > previous -> Color(0xFF10B981)
                            total < previous -> Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = deltaColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Formate "+X% vs avant" ou "= vs avant" pour le footer comparatif. */
@Composable
private fun formatDeltaPct(current: Int, previous: Int): String {
    if (previous == 0 && current == 0) return stringResource(R.string.chart_points_delta_equal)
    if (previous == 0) return stringResource(R.string.chart_points_delta_inf)
    val pct = ((current - previous).toDouble() / previous.toDouble() * 100.0).roundToInt()
    return when {
        pct == 0 -> stringResource(R.string.chart_points_delta_equal)
        pct > 0 -> stringResource(R.string.chart_points_delta_pos_format, pct)
        else -> stringResource(R.string.chart_points_delta_neg_format, pct)
    }
}

/**
 * Bar chart groupe : 7 jours (lundi..dimanche), N barres par jour (une par user).
 * L'echelle Y est partagee entre tous les users.
 */
@Composable
fun WeekdayFrequencyChart(
    data: WeekdayFrequencyResponse,
    modifier: Modifier = Modifier,
    period: WeekdayPeriod = WeekdayPeriod.ALL,
    onPeriodChange: ((WeekdayPeriod) -> Unit)? = null,
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

    val users = data.users.filter { it.counts.size == 7 }
    val maxY = users.flatMap { it.counts }.maxOrNull() ?: 0

    val dayLabels = listOf(
        stringResource(R.string.weekday_short_monday),
        stringResource(R.string.weekday_short_tuesday),
        stringResource(R.string.weekday_short_wednesday),
        stringResource(R.string.weekday_short_thursday),
        stringResource(R.string.weekday_short_friday),
        stringResource(R.string.weekday_short_saturday),
        stringResource(R.string.weekday_short_sunday),
    )

    Column(modifier = modifier) {
        if (onPeriodChange != null) {
            PeriodChips(
                current = period,
                onChange = onPeriodChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
        }
        ChartLegend(
            entries = users.mapIndexed { i, u -> u.displayName to colorFor(i) },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (users.isEmpty() || maxY <= 0) {
                drawCenterMessage(measurer, chartEmptyMsg, labelStyle)
                return@Canvas
            }

            val leftPad = with(density) { 28.dp.toPx() }
            val bottomPad = with(density) { 22.dp.toPx() }
            val topPad = with(density) { 8.dp.toPx() }
            val rightPad = with(density) { 8.dp.toPx() }
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad

            // Axe Y avec 4 graduations entieres
            val yTicks = 4
            val yMaxNice = niceCeil(maxY, yTicks)
            for (i in 0..yTicks) {
                val frac = i.toFloat() / yTicks
                val y = topPad + plotH * (1f - frac)
                drawLine(
                    color = if (i == 0) axisColor else gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(size.width - rightPad, y),
                    strokeWidth = if (i == 0) 1.5f else 1f,
                )
                val value = (yMaxNice * frac).toInt()
                val txt = measurer.measure(value.toString(), labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(leftPad - txt.size.width - 4f, y - txt.size.height / 2f),
                )
            }

            // Bars groupees : 7 groupes, N barres par groupe
            val groupW = plotW / 7f
            val innerPadFrac = 0.18f
            val innerPad = groupW * innerPadFrac
            val barAreaW = groupW - innerPad
            val n = max(1, users.size)
            val barW = barAreaW / n

            for (day in 0..6) {
                val groupX = leftPad + groupW * day + innerPad / 2f
                users.forEachIndexed { i, u ->
                    val v = u.counts.getOrNull(day) ?: 0
                    val h = if (yMaxNice > 0) plotH * (v.toFloat() / yMaxNice.toFloat()) else 0f
                    val left = groupX + barW * i
                    drawRoundRect(
                        color = colorFor(i),
                        topLeft = Offset(left + 2f, topPad + plotH - h),
                        size = Size(barW - 4f, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                    )
                }
                // Label du jour
                val txt = measurer.measure(dayLabels[day], labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(
                        groupX + (barAreaW - txt.size.width) / 2f,
                        topPad + plotH + 6f,
                    ),
                )
            }
        }

        // Sous-totaux semaine vs week-end (lun-ven vs sam-dim).
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            users.forEachIndexed { i, u ->
                val weekday = u.counts.take(5).sum()
                val weekend = u.counts.drop(5).sum()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = u.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorFor(i),
                    )
                    Text(
                        text = stringResource(R.string.chart_weekday_sublabel_format, weekday, weekend),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Chips de selection de periode pour les charts Weekday et Category. */
@Composable
fun PeriodChips(
    current: WeekdayPeriod,
    onChange: (WeekdayPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val opts = listOf(
        WeekdayPeriod.WEEK to stringResource(R.string.chart_period_week),
        WeekdayPeriod.MONTH to stringResource(R.string.chart_period_month),
        WeekdayPeriod.THREE_MONTHS to stringResource(R.string.chart_period_three_months),
        WeekdayPeriod.ALL to stringResource(R.string.chart_period_all),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        opts.forEachIndexed { i, (p, label) ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = current == p,
                onClick = { if (current != p) onChange(p) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * Frequence par categorie : top N categories par count, breakdown per-user.
 * Bars groupees comme le weekday chart (X = categories, Y = count, N bars
 * par groupe selon le nb de users).
 */
@Composable
fun CategoryFrequencyChart(
    data: CategoryFrequencyResponse,
    modifier: Modifier = Modifier,
    period: WeekdayPeriod = WeekdayPeriod.ALL,
    onPeriodChange: ((WeekdayPeriod) -> Unit)? = null,
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
    val topCategoryNone = stringResource(R.string.chart_top_category_none)

    val cats = data.categories
    val users = data.users
    val maxY = cats.flatMap { it.counts }.maxOrNull() ?: 0

    Column(modifier = modifier) {
        if (onPeriodChange != null) {
            PeriodChips(
                current = period,
                onChange = onPeriodChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
        }
        ChartLegend(
            entries = users.mapIndexed { i, u -> u.displayName to colorFor(i) },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (cats.isEmpty() || users.isEmpty() || maxY <= 0) {
                drawCenterMessage(measurer, chartEmptyMsg, labelStyle)
                return@Canvas
            }

            val leftPad = with(density) { 28.dp.toPx() }
            val bottomPad = with(density) { 50.dp.toPx() }   // labels categorie a la verticale
            val topPad = with(density) { 8.dp.toPx() }
            val rightPad = with(density) { 8.dp.toPx() }
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad

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
                val txt = measurer.measure(v.toString(), labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(leftPad - txt.size.width - 4f, y - txt.size.height / 2f),
                )
            }

            val groupW = plotW / cats.size.toFloat()
            val innerPad = groupW * 0.18f
            val barAreaW = groupW - innerPad
            val n = max(1, users.size)
            val barW = barAreaW / n

            cats.forEachIndexed { day, cat ->
                val groupX = leftPad + groupW * day + innerPad / 2f
                users.forEachIndexed { i, _ ->
                    val v = cat.counts.getOrNull(i) ?: 0
                    val h = if (yMaxNice > 0) plotH * (v.toFloat() / yMaxNice.toFloat()) else 0f
                    val left = groupX + barW * i
                    drawRoundRect(
                        color = colorFor(i),
                        topLeft = Offset(left + 2f, topPad + plotH - h),
                        size = Size(barW - 4f, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                    )
                }
                // Label categorie : tronque + ellipse si trop long.
                val rawName = cat.categoryName
                val maxChars = 10
                val name = if (rawName.length > maxChars) rawName.take(maxChars - 1) + "…" else rawName
                val txt = measurer.measure(name, labelStyle)
                drawText(
                    textLayoutResult = txt,
                    topLeft = Offset(
                        groupX + (barAreaW - txt.size.width) / 2f,
                        topPad + plotH + 6f,
                    ),
                )
            }
        }

        // Sous-totaux : pour chaque user, le top 1 categorie.
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            users.forEachIndexed { i, u ->
                // Top categorie pour ce user = celle avec le plus haut count[i].
                val top = cats.maxByOrNull { it.counts.getOrNull(i) ?: 0 }
                val topCount = top?.counts?.getOrNull(i) ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = u.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorFor(i),
                    )
                    val label = if (top != null && topCount > 0) {
                        stringResource(R.string.chart_top_category_format, top.categoryName, topCount)
                    } else topCategoryNone
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

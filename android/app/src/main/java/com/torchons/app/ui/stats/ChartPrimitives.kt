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

/** Drawing and axis primitives shared by the Stats charts. */

internal fun DrawScope.drawCenterMessage(
    measurer: TextMeasurer,
    msg: String,
    style: TextStyle,
) {
    val txt = measurer.measure(msg, style)
    drawText(
        textLayoutResult = txt,
        topLeft = Offset(
            (size.width - txt.size.width) / 2f,
            (size.height - txt.size.height) / 2f,
        ),
    )
}

/** Arrondi superieur "joli" pour Y max : multiple de yTicks proche au-dessus. */
internal fun niceCeil(maxY: Int, yTicks: Int): Int {
    if (maxY <= yTicks) return yTicks
    val step = ((maxY + yTicks - 1) / yTicks).coerceAtLeast(1)
    return step * yTicks
}

/** Selectionne jusqu'a `maxLabels` indices+libelles equirepartis sur l'axe X. */
internal fun pickAxisLabels(
    dates: List<String>,
    bucket: String,
    maxLabels: Int,
): List<Pair<Int, String>> {
    if (dates.isEmpty()) return emptyList()
    val n = dates.size
    val step = max(1, (n + maxLabels - 1) / maxLabels)
    val out = mutableListOf<Pair<Int, String>>()
    var i = 0
    while (i < n) {
        out.add(i to formatLabel(dates[i], bucket))
        i += step
    }
    // Toujours inclure le dernier point pour caler la fin de courbe.
    val lastIdx = n - 1
    if (out.lastOrNull()?.first != lastIdx) {
        out.add(lastIdx to formatLabel(dates[lastIdx], bucket))
    }
    return out
}

internal val DAY_FMT = DateTimeFormatter.ofPattern("dd/MM")

internal fun formatLabel(iso: String, bucket: String): String = try {
    val d = LocalDate.parse(iso)
    if (bucket == "week") "S${d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}"
    else d.format(DAY_FMT)
} catch (_: Exception) {
    iso.take(5)
}

package io.github.battcap.ui.chart

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.abs

/** Un point du graphique : date de fin de charge et capacité estimée. */
data class ChartPoint(val timeMs: Long, val mah: Double, val reliable: Boolean, val id: Long)

/**
 * Courbe de la capacité estimée en fonction du temps, dessinée avec Canvas
 * (aucune bibliothèque de graphiques tierce : plus léger et sans souci de licence).
 *
 * - abscisse proportionnelle au temps réel (des charges espacées restent espacées) ;
 * - point plein = mesure fiable, point creux = mesure peu fiable (fenêtre < 30 %…) ;
 * - ligne pointillée = capacité nominale, si elle est connue ;
 * - un appui sur le graphique sélectionne le point le plus proche ([onSelect]).
 */
@Composable
fun CapacityChart(
    points: List<ChartPoint>,
    designMah: Double?,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    val sorted = remember(points) { points.sortedBy { it.timeMs } }
    val currentOnSelect by rememberUpdatedState(onSelect)
    if (sorted.isEmpty()) return

    val context = LocalContext.current
    val dateFormat = remember(context) { DateFormat.getDateFormat(context) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val designColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface

    val yAxis = remember(sorted, designMah) { ChartScale.yAxis(sorted.map { it.mah } + listOfNotNull(designMah)) }
    val xAxis = remember(sorted) { ChartScale.xAxis(sorted.map { it.timeMs }) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .semantics { contentDescription = description }
            .pointerInput(sorted) {
                detectTapGestures { tap ->
                    // Retrouve la zone de tracé avec les mêmes marges que le dessin
                    val left = 56.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    fun distance(p: ChartPoint) =
                        abs(left + xAxis.fraction(p.timeMs.toDouble()).toFloat() * (right - left) - tap.x)
                    val nearest = sorted.minBy { distance(it) }
                    currentOnSelect(if (distance(nearest) < 32.dp.toPx()) nearest.id else null)
                }
            }
    ) {
        val left = 56.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        fun x(t: Long) = left + xAxis.fraction(t.toDouble()).toFloat() * (right - left)
        fun y(v: Double) = bottom - yAxis.fraction(v).toFloat() * (bottom - top)

        // Graduations Y + grille horizontale
        yAxis.ticks.forEach { v ->
            val py = y(v)
            drawLine(gridColor, Offset(left, py), Offset(right, py), strokeWidth = 1.dp.toPx())
            val label = measurer.measure("%.0f".format(v), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), py - label.size.height / 2f))
        }

        // Dates en abscisse : première, dernière, et celle du milieu s'il y a la place
        val dateTimes = buildList {
            add(sorted.first().timeMs)
            if (sorted.size >= 3) add(sorted[sorted.size / 2].timeMs)
            if (sorted.size >= 2) add(sorted.last().timeMs)
        }
        var lastLabelEnd = Float.NEGATIVE_INFINITY
        dateTimes.forEach { t ->
            val label = measurer.measure(dateFormat.format(Date(t)), labelStyle)
            val lx = (x(t) - label.size.width / 2f).coerceIn(left, right - label.size.width)
            if (lx > lastLabelEnd + 8.dp.toPx()) { // pas de chevauchement
                drawText(label, topLeft = Offset(lx, bottom + 6.dp.toPx()))
                lastLabelEnd = lx + label.size.width
            }
        }

        // Capacité nominale
        designMah?.let {
            val py = y(it)
            drawLine(
                designColor, Offset(left, py), Offset(right, py),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            )
        }

        // Courbe
        if (sorted.size >= 2) {
            val path = Path().apply {
                sorted.forEachIndexed { i, p ->
                    if (i == 0) moveTo(x(p.timeMs), y(p.mah)) else lineTo(x(p.timeMs), y(p.mah))
                }
            }
            drawPath(path, lineColor.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx()))
        }

        // Points
        val r = 4.5.dp.toPx()
        sorted.forEach { p ->
            val c = Offset(x(p.timeMs), y(p.mah))
            if (p.id == selectedId) drawCircle(lineColor.copy(alpha = 0.25f), radius = r * 2.6f, center = c)
            if (p.reliable) {
                drawCircle(lineColor, radius = r, center = c)
            } else {
                drawCircle(surface, radius = r, center = c)
                drawCircle(lineColor, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

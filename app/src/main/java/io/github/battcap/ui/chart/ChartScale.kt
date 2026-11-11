package io.github.battcap.ui.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Calcul des axes du graphique, sans dépendance Android (testable en JVM).
 * L'axe Y est arrondi à des graduations "rondes" (1, 2 ou 5 × 10^n) pour rester lisible.
 */
data class AxisRange(val min: Double, val max: Double, val step: Double) {
    val ticks: List<Double>
        get() = if (step <= 0) listOf(min, max)
        else generateSequence(min) { it + step }.takeWhile { it <= max + step / 1000 }.toList()

    fun fraction(v: Double) = if (max == min) 0.5 else (v - min) / (max - min)
}

object ChartScale {

    /** Pas "rond" le plus proche au-dessus de [raw]. */
    fun niceStep(raw: Double): Double {
        require(raw > 0)
        val exp = 10.0.pow(floor(log10(raw)))
        val f = raw / exp
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * exp
    }

    /**
     * Axe Y englobant toutes les valeurs (mesures + capacité nominale), avec une marge,
     * environ [targetTicks] graduations, et une amplitude minimale de [minSpan] (évite un
     * zoom excessif qui exagérerait des écarts de quelques mAh).
     */
    fun yAxis(values: List<Double>, targetTicks: Int = 4, minSpan: Double = 200.0): AxisRange {
        require(values.isNotEmpty())
        var lo = values.min()
        var hi = values.max()
        val span = max(hi - lo, minSpan)
        val center = (hi + lo) / 2
        lo = center - span * 0.6
        hi = center + span * 0.6
        val step = niceStep((hi - lo) / targetTicks)
        return AxisRange(floor(lo / step) * step, ceil(hi / step) * step, step)
    }

    /** Axe X (temps, ms). Un seul point : on l'entoure d'une journée de chaque côté. */
    fun xAxis(times: List<Long>): AxisRange {
        require(times.isNotEmpty())
        val lo = times.min().toDouble()
        val hi = times.max().toDouble()
        val pad = if (hi == lo) DAY_MS else (hi - lo) * 0.05
        return AxisRange(lo - pad, hi + pad, 0.0)
    }

    private const val DAY_MS = 86_400_000.0
}

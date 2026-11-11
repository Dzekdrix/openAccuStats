package io.github.battcap.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {

    @Test
    fun niceSteps() {
        assertEquals(100.0, ChartScale.niceStep(87.0), 0.0)
        assertEquals(200.0, ChartScale.niceStep(150.0), 0.0)
        assertEquals(500.0, ChartScale.niceStep(320.0), 0.0)
        assertEquals(1000.0, ChartScale.niceStep(700.0), 0.0)
    }

    @Test
    fun yAxisContainsAllValuesWithRoundTicks() {
        val values = listOf(4380.0, 4510.0, 4455.0, 5000.0)
        val axis = ChartScale.yAxis(values)
        assertTrue(axis.min <= 4380.0 && axis.max >= 5000.0)
        axis.ticks.forEach { assertEquals(0.0, it % axis.step, 1e-6) }
        assertTrue(axis.ticks.size in 3..8)
    }

    @Test
    fun yAxisSinglePointHasMinimumSpan() {
        val axis = ChartScale.yAxis(listOf(4500.0))
        assertTrue(axis.max - axis.min >= 200.0)
        assertTrue(axis.fraction(4500.0) in 0.2..0.8)
    }

    @Test
    fun xAxisSinglePointIsCentered() {
        val axis = ChartScale.xAxis(listOf(1_000_000_000L))
        assertEquals(0.5, axis.fraction(1_000_000_000.0), 1e-9)
        assertEquals(2, axis.ticks.size) // pas de boucle infinie quand step = 0
    }
}

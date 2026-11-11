package io.github.battcap.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityAverageTest {

    @Test
    fun partialChargesAreExcluded() {
        assertNull(CapacityAverage.weighted(listOf(4000.0 to 3, 4200.0 to 29)))
        assertEquals(4500.0, CapacityAverage.weighted(listOf(4500.0 to 30, 9999.0 to 4))!!, 1e-9)
    }

    @Test
    fun weightIsProportionalToLevelGained() {
        // 100 % pèse 2× plus que 50 % : (4400×100 + 4700×50) / 150 = 4500
        assertEquals(4500.0, CapacityAverage.weighted(listOf(4400.0 to 100, 4700.0 to 50))!!, 1e-9)
        // 100 % pèse 2,5× plus que 40 % : (4000×100 + 4700×40) / 140 = 4200
        assertEquals(4200.0, CapacityAverage.weighted(listOf(4000.0 to 100, 4700.0 to 40))!!, 1e-9)
    }

    @Test
    fun threshold() {
        assertTrue(CapacityAverage.isPartial(29))
        assertFalse(CapacityAverage.isPartial(30))
    }
}

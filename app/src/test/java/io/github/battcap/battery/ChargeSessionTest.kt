package io.github.battcap.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simule une charge à courant constant d'une batterie de capacité connue et vérifie
 * que l'estimation la retrouve, quelles que soient l'unité et le signe du courant.
 */
class ChargeSessionTest {

    /**
     * @param capacityMah capacité réelle simulée
     * @param currentMa   courant de charge réel
     * @param rawFactor   1000 = l'appareil rapporte des µA, 1 = des mA
     * @param rawSign     signe utilisé par l'appareil en charge
     */
    private fun simulate(
        capacityMah: Double,
        currentMa: Double,
        fromPct: Double,
        toPct: Double,
        rawFactor: Int,
        rawSign: Int,
        withCounter: Boolean = true,
        stepS: Double = 2.0
    ): ChargeSession {
        val session = ChargeSession()
        var t = 0.0
        var soc = fromPct
        while (soc < toPct) {
            val chargeUah = soc / 100.0 * capacityMah * 1000.0
            session.onSample(
                BatterySample(
                    tNanos = (t * 1e9).toLong(),
                    levelPct = soc.toInt(),
                    voltageMv = (3600 + 6 * soc).toInt(),
                    temperatureC = 30f,
                    currentRaw = (currentMa * rawFactor * rawSign).toInt(),
                    chargeCounterUah = if (withCounter) chargeUah.toInt() else null,
                    isCharging = true,
                    plugged = 1
                )
            )
            t += stepS
            soc += currentMa * stepS / 3600.0 / capacityMah * 100.0
        }
        return session
    }

    @Test
    fun microampsPositive() = check(rawFactor = 1000, rawSign = 1)

    @Test
    fun microampsNegative() = check(rawFactor = 1000, rawSign = -1)

    @Test
    fun milliampsNegative() = check(rawFactor = 1, rawSign = -1)

    private fun check(rawFactor: Int, rawSign: Int) {
        val s = simulate(4500.0, 2000.0, 20.3, 85.0, rawFactor, rawSign)
        val e = CapacityEstimator.estimate(s, designCapacityMah = 5000.0)
        assertNotNull(e)
        e!!
        assertEquals(4500.0, e.coulombMah!!, 4500.0 * 0.01)
        assertEquals(4500.0, e.chargeCounterMah!!, 4500.0 * 0.01)
        assertEquals(90.0, e.healthPct!!, 1.0)
        assertEquals(Confidence.HIGH, e.confidence)
        assertTrue(e.warnings.isEmpty())
    }

    @Test
    fun counterOnlyDevice() {
        // Appareil sans CURRENT_NOW : seul le compteur de charge est utilisable
        val session = ChargeSession()
        var soc = 10.0
        var t = 0.0
        while (soc < 70.0) {
            session.onSample(
                BatterySample((t * 1e9).toLong(), soc.toInt(), 3800, 30f, null,
                    (soc / 100 * 3000_000).toInt(), true, 1)
            )
            t += 2.0; soc += 0.02
        }
        val e = CapacityEstimator.estimate(session, null)!!
        assertEquals(3000.0, e.bestMah, 30.0)
    }

    @Test
    fun shortWindowIsLowConfidence() {
        val s = simulate(4000.0, 2000.0, 50.0, 62.0, 1000, 1)
        val e = CapacityEstimator.estimate(s, null)!!
        assertEquals(Confidence.LOW, e.confidence)
        assertTrue(Warning.SHORT_WINDOW in e.warnings)
    }
}

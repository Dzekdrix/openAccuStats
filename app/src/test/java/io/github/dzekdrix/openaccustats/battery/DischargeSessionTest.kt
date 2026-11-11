package io.github.dzekdrix.openaccustats.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simule une décharge et vérifie que l'estimation retrouve la capacité connue. */
class DischargeSessionTest {

    /**
     * Décharge à courant constant depuis [fromPct] jusqu'à [toPct].
     * @param withCurrent le courant (CURRENT_NOW) est-il exposé par l'appareil.
     */
    private fun simulate(
        capacityMah: Double,
        currentMa: Double,
        fromPct: Double,
        toPct: Double,
        withCurrent: Boolean = true,
        stepS: Double = 30.0,
        session: DischargeSession = DischargeSession()
    ): DischargeSession {
        var t = 0.0
        var soc = fromPct
        while (soc > toPct) {
            session.onSample(
                BatterySample(
                    tNanos = (t * 1e9).toLong(),
                    levelPct = soc.toInt(),
                    voltageMv = (3600 + 6 * soc).toInt(),
                    temperatureC = 28f,
                    currentRaw = if (withCurrent) (currentMa * 1000).toInt() else null,
                    chargeCounterUah = (soc / 100.0 * capacityMah * 1000.0).toInt(),
                    isCharging = false,
                    plugged = 0
                )
            )
            t += stepS
            soc -= currentMa * stepS / 3600.0 / capacityMah * 100.0
        }
        return session
    }

    @Test
    fun estimatesCapacityFromDischarge() {
        val s = simulate(4200.0, 300.0, 90.0, 20.0)
        val e = CapacityEstimator.estimateDischarge(s, designCapacityMah = 4500.0)
        assertNotNull(e)
        e!!
        assertEquals(4200.0, e.coulombMah!!, 4200.0 * 0.02)
        assertEquals(4200.0, e.chargeCounterMah!!, 4200.0 * 0.02)
        assertEquals(90, e.startLevel)
        assertEquals(20, e.endLevel)
        // chargeInMah = charge réellement sortie pendant ce cycle (≈ 70 % de la capacité)
        assertEquals(e.bestMah * 0.70, e.chargeInMah, e.bestMah * 0.02)
        assertTrue("pas d'énergie estimée en décharge (pas de tension moyenne)", e.energyWh == null)
        assertNull(e.energyInWh)
    }

    @Test
    fun counterOnlyDeviceStillWorks() {
        // Pas de CURRENT_NOW : seul le compteur de charge permet l'estimation
        val s = simulate(3800.0, 250.0, 80.0, 25.0, withCurrent = false)
        val e = CapacityEstimator.estimateDischarge(s, null)!!
        // Tolérance plus large qu'à 1 % : seul le dernier changement de niveau entier est capté,
        // ce qui introduit une erreur de quantification de l'ordre de 1 / Δniveau.
        assertEquals(3800.0, e.bestMah, 3800.0 * 0.03)
        assertNull(e.coulombMah)
    }

    @Test
    fun shortDischargeIsPartial() {
        val s = simulate(4000.0, 300.0, 80.0, 70.0)
        val e = CapacityEstimator.estimateDischarge(s, null)!!
        assertTrue(CapacityAverage.isPartial(e.startLevel - e.endLevel))
    }

    @Test
    fun risingLevelIsIgnored() {
        // Le niveau remonte (ex. lecture parasite) : pas une décharge exploitable
        val session = DischargeSession()
        session.onSample(BatterySample(0L, 50, 3800, 28f, 300_000, 2_000_000, false, 0))
        session.onSample(BatterySample(60_000_000_000L, 55, 3820, 28f, 300_000, 2_200_000, false, 0))
        assertNull(session.snapshot)
    }
}

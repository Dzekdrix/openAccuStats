package io.github.battcap.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simule des charges d'une batterie de capacité connue et vérifie que l'estimation
 * la retrouve, quelles que soient l'unité et le signe du courant.
 */
class ChargeSessionTest {

    /**
     * Charge à courant constant jusqu'à [toPct], puis (si [taperToFull]) phase à tension
     * constante simulée : courant décroissant à 100 %.
     *
     * @param rawFactor 1000 = l'appareil rapporte des µA, 1 = des mA
     * @param rawSign   signe utilisé par l'appareil en charge
     */
    private fun simulate(
        capacityMah: Double,
        currentMa: Double,
        fromPct: Double,
        toPct: Double,
        rawFactor: Int,
        rawSign: Int,
        withCounter: Boolean = true,
        stepS: Double = 2.0,
        taperToFull: Boolean = false,
        session: ChargeSession = ChargeSession()
    ): ChargeSession {
        var t = 0.0
        var soc = fromPct
        fun sample(i: Double, level: Int) = session.onSample(
            BatterySample(
                tNanos = (t * 1e9).toLong(),
                levelPct = level,
                voltageMv = (3600 + 6 * soc).toInt(),
                temperatureC = 30f,
                currentRaw = (i * rawFactor * rawSign).toInt(),
                chargeCounterUah = if (withCounter) (soc / 100.0 * capacityMah * 1000.0).toInt() else null,
                isCharging = true,
                plugged = 1
            )
        )
        while (soc < toPct) {
            sample(currentMa, soc.toInt())
            t += stepS
            soc += currentMa * stepS / 3600.0 / capacityMah * 100.0
        }
        if (taperToFull) {
            var i = currentMa
            repeat(600) { // 20 min de phase à tension constante
                sample(i, 100)
                t += stepS
                i *= 0.99
            }
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
        // Charge réellement entrée = capacité × fraction mesurée (≈ 21 % → 85 %)
        assertEquals(e.bestMah * e.deltaSocPct / 100.0, e.chargeInMah, 1e-6)
        assertTrue(e.chargeInMah < e.bestMah)
        assertEquals(e.chargeInMah * e.meanVoltageV!! / 1000.0, e.energyInWh!!, 1e-6)
        assertEquals(e.bestMah * e.meanVoltageV!! / 1000.0, e.energyWh!!, 1e-6)
        assertEquals(Confidence.HIGH, e.confidence)
        assertTrue(e.warnings.isEmpty())
        assertFalse(e.reachedFull)
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

    @Test
    fun sparseSamplingStillAnchors() {
        // Échantillons espacés : le niveau saute de plusieurs points entre deux lectures
        val s = simulate(4000.0, 3000.0, 20.0, 80.0, 1000, 1, stepS = 50.0)
        val e = CapacityEstimator.estimate(s, null)
        assertNotNull(e)
        assertEquals(4000.0, e!!.chargeCounterMah!!, 4000.0 * 0.03)
    }

    @Test
    fun fullDetectedFromCurrentTaper() {
        val s = simulate(4000.0, 2000.0, 40.0, 100.0, 1000, -1, taperToFull = true)
        assertTrue(s.reachedFull)
        assertTrue(CapacityEstimator.estimate(s, null)!!.reachedFull)
    }

    @Test
    fun fullDetectedFromStatus() {
        val s = simulate(4000.0, 2000.0, 60.0, 99.5, 1000, 1)
        assertFalse(s.reachedFull)
        s.onSample(BatterySample(Long.MAX_VALUE / 2, 100, 4350, 30f, 0, 4_000_000, true, 1, isFull = true))
        assertTrue(s.reachedFull)
    }

    @Test
    fun chargeLimitIsNotFull() {
        // Limite de charge à 80 % (protection batterie) : pas une charge complète
        val s = simulate(4000.0, 2000.0, 20.0, 80.0, 1000, 1)
        assertFalse(s.reachedFull)
    }

    @Test
    fun displayCurrentWhileDischarging() {
        // Avant toute charge : l'unité est apprise en décharge, le courant s'affiche négatif
        val n = CurrentNormalizer()
        assertNull(n.displayMa(450_000, charging = false))
        repeat(5) { n.feed(450_000, charging = false) }
        assertEquals(-450.0, n.displayMa(450_000, charging = false)!!, 0.1)
        assertEquals(-450.0, n.displayMa(-450_000, charging = false)!!, 0.1)
    }

    @Test
    fun trickleChargeAfter100PctIsCounted() {
        // Après 100 %, le chargeur continue à courant constant : cette charge doit être comptée
        // jusqu'au dernier échantillon, même si le niveau entier ne bouge plus. Le compteur de
        // la jauge, lui, sature à la capacité nominale dès 100 % (comportement réel le plus
        // courant) : seule l'intégration du courant peut voir cette charge supplémentaire.
        val capacityMah = 4000.0
        val currentMa = 2000.0
        val session = ChargeSession()
        var t = 0.0
        var inputMah = 0.2 * capacityMah // charge réellement entrée depuis le début, sans plafond
        fun sample() {
            val level = minOf(100, (inputMah / capacityMah * 100.0).toInt())
            session.onSample(
                BatterySample(
                    tNanos = (t * 1e9).toLong(), levelPct = level, voltageMv = 3900,
                    temperatureC = 30f, currentRaw = (currentMa * 1000).toInt(),
                    chargeCounterUah = (minOf(inputMah, capacityMah) * 1000.0).toInt(),
                    isCharging = true, plugged = 1
                )
            )
        }
        sample() // amorce à 20 % (pas encore ancré)
        // Échantillons rapprochés (2 s) jusqu'à 100 %, pour ne pas déclencher la détection de trou.
        while (inputMah < capacityMah) {
            t += 2.0
            inputMah += currentMa * 2.0 / 3600.0
            sample()
        }
        val before = CapacityEstimator.estimate(session, null)!!
        assertEquals(100, before.endLevel)

        // Phase à tension constante : le niveau entier reste bloqué à 100 % et le compteur de
        // la jauge aussi (saturé), mais le courant continue de circuler.
        repeat(1000) {
            t += 2.0
            inputMah += currentMa * 2.0 / 3600.0
            sample()
        }
        val after = CapacityEstimator.estimate(session, null)!!
        assertEquals(100, after.endLevel)
        assertTrue("la charge supplémentaire après 100 % doit être comptée", after.bestMah > before.bestMah)
    }

    @Test
    fun idleCalibrationInMilliampsIsCorrectedByCharge() {
        // Écran éteint, 8 mA en µA (8000) : pris à tort pour des mA, puis corrigé dès la charge
        val n = CurrentNormalizer()
        repeat(5) { n.feed(8_000, charging = false) }
        assertEquals(1000, n.factorToUa)
        val s = simulate(4500.0, 2000.0, 30.0, 80.0, 1000, 1, session = ChargeSession(n))
        assertEquals(1, n.factorToUa)
        assertEquals(4500.0, CapacityEstimator.estimate(s, null)!!.coulombMah!!, 45.0)
    }
}

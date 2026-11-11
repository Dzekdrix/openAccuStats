package io.github.battcap.battery

import kotlin.math.abs

enum class Confidence { LOW, MEDIUM, HIGH }

enum class Warning { SHORT_WINDOW, GAPS, HOT, METHODS_DISAGREE, ABOVE_DESIGN }

data class CapacityEstimate(
    val startLevel: Int,
    val endLevel: Int,
    val bestMah: Double,                   // valeur retenue
    val coulombMah: Double?,               // méthode 1 : intégration du courant
    val chargeCounterMah: Double?,         // méthode 2 : delta du compteur de la jauge
    val energyWh: Double?,                 // énergie stockable estimée (côté batterie)
    val meanVoltageV: Double?,
    val healthPct: Double?,                // vs capacité nominale si connue
    val durationS: Double,
    val maxTempC: Float,
    val confidence: Confidence,
    val warnings: List<Warning>
) {
    val deltaSocPct get() = endLevel - startLevel
}

object CapacityEstimator {

    private const val AGREEMENT = 0.10

    fun estimate(session: ChargeSession, designCapacityMah: Double?): CapacityEstimate? {
        val snap = session.snapshot ?: return null
        val dSoc = snap.endLevel - snap.startLevel
        if (dSoc <= 0) return null
        val frac = dSoc / 100.0

        val qCoulomb = snap.chargeInMah.takeIf { it > 0 }?.div(frac)
        val qCc = snap.chargeCounterDeltaMah?.takeIf { it > 0 }?.div(frac)
        val agree = qCoulomb != null && qCc != null && abs(qCc - qCoulomb) / qCoulomb <= AGREEMENT

        // Si les deux méthodes concordent, on retient le compteur de la jauge (intégré en hardware,
        // à haute fréquence). Sinon on garde notre intégration, qui est une mesure indépendante :
        // sur certains appareils CHARGE_COUNTER est simplement recalculé à partir du niveau.
        val best = when {
            agree -> qCc!!
            qCoulomb != null -> qCoulomb
            qCc != null -> qCc
            else -> return null
        }

        val energyWh = snap.energyInWh.takeIf { it > 0 }?.div(frac)

        val warnings = buildList {
            if (dSoc < 30) add(Warning.SHORT_WINDOW)
            if (session.gapsDetected > 0) add(Warning.GAPS)
            if (session.maxTempC > 40f) add(Warning.HOT)
            if (qCoulomb != null && qCc != null && !agree) add(Warning.METHODS_DISAGREE)
            if (designCapacityMah != null && best > designCapacityMah * 1.15) add(Warning.ABOVE_DESIGN)
        }

        val confidence = when {
            dSoc >= 50 && warnings.isEmpty() -> Confidence.HIGH
            dSoc >= 30 && Warning.ABOVE_DESIGN !in warnings -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return CapacityEstimate(
            startLevel = snap.startLevel,
            endLevel = snap.endLevel,
            bestMah = best,
            coulombMah = qCoulomb,
            chargeCounterMah = qCc,
            energyWh = energyWh,
            meanVoltageV = if (snap.chargeInMah > 0 && snap.energyInWh > 0)
                snap.energyInWh * 1000.0 / snap.chargeInMah else null,
            healthPct = designCapacityMah?.let { best / it * 100.0 },
            durationS = snap.durationS,
            maxTempC = session.maxTempC,
            confidence = confidence,
            warnings = warnings
        )
    }
}

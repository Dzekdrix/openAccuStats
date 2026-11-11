package io.github.dzekdrix.openaccustats.battery

/**
 * Suit une décharge : la charge restituée par la batterie entre le débranchement et le
 * prochain branchement. Même principe que [ChargeSession], le niveau descendant cette fois :
 * l'intégration démarre au débranchement et chaque nouveau niveau inférieur fige un résultat.
 *
 * Le compteur de charge de la jauge est la méthode principale : il ne dépend pas de la
 * continuité des échantillons (le téléphone dort entre deux lectures). L'intégration du courant
 * ignore les trous de mesure, elle sert de second avis et de secours.
 *
 * @param normalizer partagé avec le service ; le signe du courant s'apprend en charge, donc
 *        en décharge on prend l'amplitude (abs) sans se soucier du signe.
 */
class DischargeSession(
    private val normalizer: CurrentNormalizer = CurrentNormalizer(),
    private val maxGapSeconds: Double = 300.0
) {
    data class Snapshot(
        val startLevel: Int,
        val endLevel: Int,
        val dischargedMah: Double,            // ∫ |I| dt (0 si courant inconnu)
        val counterDeltaMah: Double?,         // baisse du compteur de charge
        val durationS: Double
    )

    private var prev: BatterySample? = null
    private var prevMagUa: Long? = null
    private var anchored = false
    private var anchorTimeNs = 0L
    private var startLevel = -1
    private var lastObservedLevel = -1
    private var counterStart: Int? = null
    private var dischargedUah = 0.0

    var snapshot: Snapshot? = null; private set
    var gapsDetected = 0; private set
    var maxTempC = Float.NEGATIVE_INFINITY; private set

    fun onSample(s: BatterySample) {
        s.currentRaw?.let { normalizer.feed(it, charging = false) }
        if (s.isCharging) { prev = null; return } // le service termine la décharge avant la charge

        maxTempC = maxOf(maxTempC, s.temperatureC)
        val magUa = s.currentRaw?.let { normalizer.magnitudeUa(it) }

        // Ancrage au premier échantillon débranché : niveau de départ = niveau au débranchement
        if (!anchored) {
            anchored = true
            anchorTimeNs = s.tNanos
            startLevel = s.levelPct
            lastObservedLevel = s.levelPct
            counterStart = s.chargeCounterUah
            prev = s; prevMagUa = magUa
            return
        }

        val p = prev
        if (p != null && magUa != null) {
            val dtS = (s.tNanos - p.tNanos) / 1e9
            if (dtS > maxGapSeconds) {
                gapsDetected++ // trou de mesure : on n'invente pas de données
            } else if (dtS > 0) {
                val iAvgUa = (magUa + (prevMagUa ?: magUa)) / 2.0
                dischargedUah += iAvgUa * dtS / 3600.0
            }
        }

        // Nouveau niveau inférieur => fin de fenêtre candidate
        if (s.levelPct >= 0 && s.levelPct < lastObservedLevel) {
            lastObservedLevel = s.levelPct
            val cc0 = counterStart
            val cc1 = s.chargeCounterUah
            snapshot = Snapshot(
                startLevel = startLevel,
                endLevel = s.levelPct,
                dischargedMah = dischargedUah / 1000.0,
                counterDeltaMah = if (cc0 != null && cc1 != null) (cc0 - cc1) / 1000.0 else null,
                durationS = (s.tNanos - anchorTimeNs) / 1e9
            )
        }

        prev = s; prevMagUa = magUa
    }
}

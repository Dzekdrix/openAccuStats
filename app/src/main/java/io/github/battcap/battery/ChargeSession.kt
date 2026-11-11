package io.github.battcap.battery

/**
 * Intègre le courant (coulomb counting) et la puissance pendant une charge.
 *
 * Le niveau Android est un ENTIER : une fenêtre 20→80 % aurait jusqu'à ±3 % d'erreur
 * relative. On ancre donc la mesure sur les TRANSITIONS de niveau : on commence à
 * intégrer à l'instant où le niveau passe de N à N+1, et chaque transition suivante
 * fige un résultat candidat. L'erreur de quantification devient quasi nulle.
 */
class ChargeSession(private val maxGapSeconds: Double = 60.0) {

    private val normalizer = CurrentNormalizer()
    private var prev: BatterySample? = null
    private var prevCurrentUa: Long? = null

    private var anchorStarted = false
    private var anchorTimeNs = 0L
    private var startLevel = -1
    private var lastObservedLevel = -1
    private var ccStart: Int? = null

    private var chargeUah = 0.0
    private var energyUwh = 0.0

    var snapshot: Snapshot? = null; private set
    var gapsDetected = 0; private set
    var maxTempC = Float.NEGATIVE_INFINITY; private set
    var lastPlugged = 0; private set

    data class Snapshot(
        val startLevel: Int,
        val endLevel: Int,
        val chargeInMah: Double,              // ∫ I dt (0 si courant non supporté)
        val energyInWh: Double,               // ∫ V·I dt, côté batterie (pas côté prise)
        val chargeCounterDeltaMah: Double?,
        val durationS: Double
    )

    /** Courant instantané normalisé (µA, positif = entrant), null tant que non calibré. */
    fun normalizedCurrentUa(raw: Int?): Long? = raw?.let { normalizer.toChargingUa(it) }

    fun onSample(s: BatterySample) {
        if (!s.isCharging) { prev = s; prevCurrentUa = null; return }
        maxTempC = maxOf(maxTempC, s.temperatureC)
        lastPlugged = s.plugged

        s.currentRaw?.let { if (!normalizer.isCalibrated) normalizer.feed(it) }
        val iUa = s.currentRaw?.let { normalizer.toChargingUa(it) }
        val p = prev

        // 1) Ancrage sur la première transition de niveau.
        //    Si le courant n'est pas exposé, on ancre quand même : le compteur de charge peut suffire.
        if (!anchorStarted) {
            val currentReady = normalizer.isCalibrated || s.currentRaw == null
            if (currentReady && p != null && p.isCharging && s.levelPct == p.levelPct + 1) {
                anchorStarted = true
                anchorTimeNs = s.tNanos
                startLevel = s.levelPct
                lastObservedLevel = s.levelPct
                ccStart = s.chargeCounterUah
            }
            prev = s; prevCurrentUa = iUa
            return
        }

        // 2) Intégration par trapèzes
        if (p != null && iUa != null) {
            val dtS = (s.tNanos - p.tNanos) / 1e9
            if (dtS > maxGapSeconds) {
                gapsDetected++ // trou de mesure : on n'invente pas de données
            } else if (dtS > 0) {
                val iAvgUa = (iUa + (prevCurrentUa ?: iUa)) / 2.0
                val vAvgV = (s.voltageMv + p.voltageMv) / 2.0 / 1000.0
                val dtH = dtS / 3600.0
                chargeUah += iAvgUa * dtH
                energyUwh += iAvgUa * vAvgV * dtH
            }
        }

        // 3) Nouvelle transition => fin de fenêtre candidate
        if (s.levelPct > lastObservedLevel) {
            lastObservedLevel = s.levelPct
            val ccEnd = s.chargeCounterUah
            val cc0 = ccStart
            snapshot = Snapshot(
                startLevel = startLevel,
                endLevel = s.levelPct,
                chargeInMah = chargeUah / 1000.0,
                energyInWh = energyUwh / 1e6,
                chargeCounterDeltaMah = if (cc0 != null && ccEnd != null) (ccEnd - cc0) / 1000.0 else null,
                durationS = (s.tNanos - anchorTimeNs) / 1e9
            )
        }

        prev = s; prevCurrentUa = iUa
    }
}

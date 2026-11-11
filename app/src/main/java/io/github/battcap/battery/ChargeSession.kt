package io.github.battcap.battery

/**
 * Intègre le courant (coulomb counting) et la puissance pendant une charge.
 *
 * Le niveau Android est un ENTIER : une fenêtre 20→80 % aurait jusqu'à ±3 % d'erreur
 * relative. On ancre donc la mesure sur les CHANGEMENTS de niveau : on commence à
 * intégrer à l'instant où le niveau augmente, et chaque augmentation suivante
 * fige un résultat candidat. L'erreur de quantification devient quasi nulle.
 *
 * @param normalizer partagé avec le service : l'unité et le signe du courant sont des
 *        propriétés de l'appareil, inutile de les réapprendre à chaque charge.
 */
class ChargeSession(
    private val normalizer: CurrentNormalizer = CurrentNormalizer(),
    private val maxGapSeconds: Double = 60.0
) {
    private var prev: BatterySample? = null
    private var prevCurrentUa: Long? = null

    private var anchorStarted = false
    private var anchorTimeNs = 0L
    private var startLevel = -1
    private var lastObservedLevel = -1
    private var ccStart: Int? = null

    private var chargeUah = 0.0
    private var energyUwh = 0.0

    private var peakCurrentUa = 0L
    private var taperSinceNs: Long? = null

    var snapshot: Snapshot? = null; private set
    var gapsDetected = 0; private set
    var maxTempC = Float.NEGATIVE_INFINITY; private set
    var lastPlugged = 0; private set

    /**
     * Fin de charge réellement atteinte : statut FULL rapporté par le système, ou, à 100 %,
     * courant retombé sous 10 % du courant maximal de la charge pendant au moins une minute
     * (fin de la phase à tension constante du chargeur Li-ion).
     */
    var reachedFull = false; private set

    data class Snapshot(
        val startLevel: Int,
        val endLevel: Int,
        val chargeInMah: Double,              // ∫ I dt (0 si courant non supporté)
        val energyInWh: Double,               // ∫ V·I dt, côté batterie (pas côté prise)
        val chargeCounterDeltaMah: Double?,
        val durationS: Double
    )

    fun onSample(s: BatterySample) {
        s.currentRaw?.let { normalizer.feed(it, charging = s.isCharging && !s.isFull) }
        if (!s.isCharging) {
            prev = s; prevCurrentUa = null; taperSinceNs = null
            return
        }
        maxTempC = maxOf(maxTempC, s.temperatureC)
        lastPlugged = s.plugged

        val iUa = s.currentRaw?.let { normalizer.toChargingUa(it) }
        updateFullDetection(s, iUa)
        val p = prev

        // 1) Ancrage sur le premier changement de niveau.
        //    Si le courant n'est pas exposé, on ancre quand même : le compteur de charge peut suffire.
        if (!anchorStarted) {
            val currentReady = normalizer.isCalibrated || s.currentRaw == null
            if (currentReady && p != null && p.isCharging && s.levelPct > p.levelPct) {
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

        // 3) Nouveau niveau => fin de fenêtre candidate
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

    private fun updateFullDetection(s: BatterySample, iUa: Long?) {
        if (s.isFull) { reachedFull = true; return }
        if (iUa == null) return
        peakCurrentUa = maxOf(peakCurrentUa, iUa)
        val tapering = s.levelPct >= 100 && peakCurrentUa >= MIN_PEAK_UA &&
                iUa < peakCurrentUa * TAPER_RATIO
        if (!tapering) { taperSinceNs = null; return }
        val since = taperSinceNs ?: s.tNanos.also { taperSinceNs = it }
        if (s.tNanos - since >= TAPER_HOLD_NS) reachedFull = true
    }

    private companion object {
        const val MIN_PEAK_UA = 300_000L          // en dessous, la notion de "chute" n'a pas de sens
        const val TAPER_RATIO = 0.10
        const val TAPER_HOLD_NS = 60_000_000_000L // 1 min
    }
}

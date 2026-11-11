package io.github.battcap.battery

import kotlin.math.abs
import kotlin.math.sign

/**
 * CURRENT_NOW n'est pas standardisé en pratique :
 *  - unité : µA (norme AOSP) mais certains OEM renvoient des mA ;
 *  - signe : la doc dit "positif = entrant", mais beaucoup d'appareils font l'inverse.
 *
 * Calibration sur les premiers échantillons pris EN CHARGE :
 *  - un téléphone en charge absorbe au moins ~50 mA ; si la médiane |raw| < 10 000,
 *    ce sont forcément des mA (10 000 µA = 10 mA, irréaliste en charge) ;
 *  - le signe majoritaire pendant la charge définit "entrant".
 */
class CurrentNormalizer(private val calibrationSamples: Int = 12) {

    private val buffer = mutableListOf<Int>()
    var factorToUa: Int? = null; private set   // 1 (µA) ou 1000 (mA)
    var chargingSign: Int? = null; private set // +1 ou -1

    val isCalibrated get() = factorToUa != null && chargingSign != null

    fun feed(raw: Int) {
        if (isCalibrated) return
        buffer += raw
        if (buffer.size >= calibrationSamples) {
            val median = buffer.map { abs(it) }.sorted()[buffer.size / 2]
            factorToUa = if (median < 10_000) 1000 else 1
            chargingSign = buffer.sumOf { it.sign }.sign.takeIf { it != 0 } ?: 1
            buffer.clear()
        }
    }

    /** Courant normalisé en µA, positif = charge entrant dans la batterie. */
    fun toChargingUa(raw: Int): Long? {
        val f = factorToUa ?: return null
        val s = chargingSign ?: return null
        return raw.toLong() * f * s
    }
}

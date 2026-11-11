package io.github.battcap.battery

import kotlin.math.abs
import kotlin.math.sign

/**
 * CURRENT_NOW n'est pas standardisé en pratique :
 *  - unité : µA (norme AOSP) mais certains OEM renvoient des mA ;
 *  - signe : la doc dit "positif = entrant", mais beaucoup d'appareils font l'inverse.
 *
 * Unité : déterminée sur n'importe quels échantillons (charge ou décharge), car seule
 * l'amplitude compte. Médiane |raw| < 10 000 => mA. Comme un téléphone au repos écran éteint
 * peut consommer moins de 10 mA, la décision est corrigée dès qu'une valeur > 20 000 apparaît :
 * 20 000 mA (20 A) est physiquement impossible, ce sont donc forcément des µA.
 *
 * Signe : déterminé uniquement EN CHARGE (le signe majoritaire définit "entrant").
 */
class CurrentNormalizer(
    private val unitSamples: Int = 5,
    private val signSamples: Int = 12
) {
    private val magnitudes = mutableListOf<Int>()
    private val signs = mutableListOf<Int>()

    var factorToUa: Int? = null; private set   // 1 (µA) ou 1000 (mA)
    var chargingSign: Int? = null; private set // +1 ou -1

    val isCalibrated get() = factorToUa != null && chargingSign != null

    /** @param charging true seulement si le statut est CHARGING (pas FULL, pas débranché). */
    fun feed(raw: Int, charging: Boolean) {
        if (abs(raw) > 20_000) {
            factorToUa = 1
            magnitudes.clear()
        } else if (factorToUa == null) {
            magnitudes += abs(raw)
            if (magnitudes.size >= unitSamples) {
                factorToUa = if (magnitudes.sorted()[magnitudes.size / 2] < 10_000) 1000 else 1
                magnitudes.clear()
            }
        }
        if (charging && chargingSign == null && raw != 0) {
            signs += raw.sign
            if (signs.size >= signSamples) {
                chargingSign = signs.sum().sign.takeIf { it != 0 } ?: 1
                signs.clear()
            }
        }
    }

    /** Courant normalisé en µA, positif = charge entrant dans la batterie. */
    fun toChargingUa(raw: Int): Long? {
        val f = factorToUa ?: return null
        val s = chargingSign ?: return null
        return raw.toLong() * f * s
    }

    /**
     * Courant à afficher (mA, positif = entrant). Avant que le signe soit connu,
     * on se fie au statut : en charge le courant entre, sinon il sort.
     */
    fun displayMa(raw: Int, charging: Boolean): Double? {
        val f = factorToUa ?: return null
        val s = chargingSign
        return when {
            s != null -> raw.toLong() * f * s / 1000.0
            charging -> abs(raw.toLong()) * f / 1000.0
            else -> -abs(raw.toLong()) * f / 1000.0
        }
    }
}

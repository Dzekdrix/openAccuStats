package io.github.battcap.battery

/**
 * Moyenne de la capacité sur plusieurs charges.
 *
 * - Seules les charges couvrant au moins [MIN_DELTA_SOC] % de niveau sont prises en compte :
 *   en dessous, l'erreur relative de la mesure est trop grande.
 * - Chaque charge est pondérée par sa variation de niveau : une charge de 50 % pèse deux fois
 *   moins qu'une charge de 100 %. Physiquement, cela revient à diviser la charge totale entrée
 *   sur toutes ces charges par la variation de niveau totale :
 *   Σ(capacité × Δ) / ΣΔ = Σ(charge entrée × 100) / ΣΔ.
 */
object CapacityAverage {

    /** Seuil en dessous duquel une charge est « partielle » : affichée mais hors moyenne. */
    const val MIN_DELTA_SOC = 30

    fun isPartial(deltaSocPct: Int) = deltaSocPct < MIN_DELTA_SOC

    /** @param entries paires (capacité estimée en mAh, variation de niveau en %) */
    fun weighted(entries: List<Pair<Double, Int>>): Double? {
        val counted = entries.filter { (_, delta) -> !isPartial(delta) }
        if (counted.isEmpty()) return null
        val totalWeight = counted.sumOf { it.second }.toDouble()
        return counted.sumOf { (mah, delta) -> mah * delta } / totalWeight
    }
}

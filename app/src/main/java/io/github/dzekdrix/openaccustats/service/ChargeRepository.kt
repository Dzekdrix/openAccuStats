package io.github.dzekdrix.openaccustats.service

import io.github.dzekdrix.openaccustats.battery.BatterySample
import io.github.dzekdrix.openaccustats.battery.CapacityEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ce qu'il est advenu de la dernière charge, pour expliquer à l'utilisateur pourquoi elle est (ou non) dans l'historique. */
sealed interface SessionOutcome {
    data class Saved(val mah: Double, val deltaSocPct: Int, val reachedFull: Boolean) : SessionOutcome
    data object NoMeasurement : SessionOutcome
}

/** État en direct partagé entre le service de mesure et l'interface. */
object ChargeRepository {
    private val _running = MutableStateFlow(false)
    private val _sample = MutableStateFlow<BatterySample?>(null)
    private val _currentMa = MutableStateFlow<Double?>(null)
    private val _estimate = MutableStateFlow<CapacityEstimate?>(null)
    private val _dischargeEstimate = MutableStateFlow<CapacityEstimate?>(null)
    private val _outcome = MutableStateFlow<SessionOutcome?>(null)
    private val _uiVisible = MutableStateFlow(false)

    val running: StateFlow<Boolean> = _running.asStateFlow()
    val lastSample: StateFlow<BatterySample?> = _sample.asStateFlow()
    /** Courant affichable (mA, positif = entrant), null tant que l'unité n'est pas déterminée. */
    val currentMa: StateFlow<Double?> = _currentMa.asStateFlow()
    val estimate: StateFlow<CapacityEstimate?> = _estimate.asStateFlow()
    /** Estimation en direct pendant une décharge en cours (null hors décharge). */
    val dischargeEstimate: StateFlow<CapacityEstimate?> = _dischargeEstimate.asStateFlow()
    val lastOutcome: StateFlow<SessionOutcome?> = _outcome.asStateFlow()
    /** L'écran est affiché : le service échantillonne alors vite même sans chargeur. */
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    internal fun setRunning(v: Boolean) { _running.value = v }
    internal fun publishSample(s: BatterySample, currentMa: Double?) {
        _sample.value = s; _currentMa.value = currentMa
    }
    internal fun publishEstimate(e: CapacityEstimate?) { _estimate.value = e }
    internal fun publishDischargeEstimate(e: CapacityEstimate?) { _dischargeEstimate.value = e }
    internal fun publishOutcome(o: SessionOutcome) { _outcome.value = o }
    internal fun setUiVisible(v: Boolean) { _uiVisible.value = v }
}

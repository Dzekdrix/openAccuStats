package io.github.battcap.service

import io.github.battcap.battery.BatterySample
import io.github.battcap.battery.CapacityEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État en direct partagé entre le service de mesure et l'interface. */
object ChargeRepository {
    private val _running = MutableStateFlow(false)
    private val _sample = MutableStateFlow<BatterySample?>(null)
    private val _currentMa = MutableStateFlow<Double?>(null)
    private val _estimate = MutableStateFlow<CapacityEstimate?>(null)

    val running: StateFlow<Boolean> = _running.asStateFlow()
    val lastSample: StateFlow<BatterySample?> = _sample.asStateFlow()
    /** Courant normalisé (mA, positif = charge), null tant que la calibration n'est pas faite. */
    val currentMa: StateFlow<Double?> = _currentMa.asStateFlow()
    val estimate: StateFlow<CapacityEstimate?> = _estimate.asStateFlow()

    internal fun setRunning(v: Boolean) { _running.value = v }
    internal fun publishSample(s: BatterySample, currentMa: Double?) {
        _sample.value = s; _currentMa.value = currentMa
    }
    internal fun publishEstimate(e: CapacityEstimate?) { _estimate.value = e }
}

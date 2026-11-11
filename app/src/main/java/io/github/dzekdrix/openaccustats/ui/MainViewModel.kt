package io.github.dzekdrix.openaccustats.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dzekdrix.openaccustats.battery.BatteryReader
import io.github.dzekdrix.openaccustats.battery.CapacityAverage
import io.github.dzekdrix.openaccustats.battery.CurrentNormalizer
import io.github.dzekdrix.openaccustats.data.AppDatabase
import io.github.dzekdrix.openaccustats.data.ChargeRecord
import io.github.dzekdrix.openaccustats.data.Settings
import io.github.dzekdrix.openaccustats.data.deltaSocPct
import io.github.dzekdrix.openaccustats.service.ChargeRepository
import io.github.dzekdrix.openaccustats.service.MonitoringController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).chargeRecords()
    private val settings = Settings(app)

    /** Suivi activé par l'utilisateur (même si aucun service ne tourne pendant l'attente). */
    val running = MonitoringController.enabled
    val sample = ChargeRepository.lastSample
    val currentMa = ChargeRepository.currentMa
    val estimate = ChargeRepository.estimate
    val dischargeEstimate = ChargeRepository.dischargeEstimate
    val lastOutcome = ChargeRepository.lastOutcome

    val history: StateFlow<List<ChargeRecord>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val detectedDesignMah: Double? = settings.detectedDesignMah
    private val _manualDesignMah = MutableStateFlow(settings.manualDesignMah)
    val manualDesignMah: StateFlow<Double?> = _manualDesignMah.asStateFlow()

    init {
        MonitoringController.refresh(app)
        liveReadingsWhileVisible()
    }

    /**
     * Mesures en direct quand l'écran est affiché et qu'aucun service ne tourne
     * (suivi désactivé, ou attente silencieuse d'une charge). Sinon c'est le service qui publie.
     */
    private fun liveReadingsWhileVisible() = viewModelScope.launch {
        val reader = BatteryReader(getApplication())
        val normalizer = CurrentNormalizer()
        ChargeRepository.uiVisible.collectLatest { visible ->
            while (visible) {
                if (!ChargeRepository.running.value) {
                    reader.read()?.let { s ->
                        s.currentRaw?.let { normalizer.feed(it, charging = s.isCharging && !s.isFull) }
                        ChargeRepository.publishSample(s, s.currentRaw?.let { normalizer.displayMa(it, s.isCharging) })
                    }
                }
                delay(2_000)
            }
        }
    }

    fun start() = MonitoringController.enable(getApplication())
    fun stop() = MonitoringController.disable(getApplication())

    fun setManualDesign(mah: Double?) {
        settings.manualDesignMah = mah
        _manualDesignMah.value = mah
    }

    fun delete(record: ChargeRecord) = viewModelScope.launch { dao.delete(record) }

    companion object {
        /** Moyenne pondérée par la variation de niveau, charges partielles exclues (voir CapacityAverage). */
        fun weightedAverage(records: List<ChargeRecord>): Double? =
            CapacityAverage.weighted(records.map { it.bestMah to it.deltaSocPct })
    }
}

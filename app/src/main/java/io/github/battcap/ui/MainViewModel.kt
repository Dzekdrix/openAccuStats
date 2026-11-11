package io.github.battcap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.battcap.battery.Confidence
import io.github.battcap.data.AppDatabase
import io.github.battcap.data.ChargeRecord
import io.github.battcap.data.Settings
import io.github.battcap.service.ChargeMonitorService
import io.github.battcap.service.ChargeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).chargeRecords()
    private val settings = Settings(app)

    val running = ChargeRepository.running
    val sample = ChargeRepository.lastSample
    val currentMa = ChargeRepository.currentMa
    val estimate = ChargeRepository.estimate
    val lastOutcome = ChargeRepository.lastOutcome

    val history: StateFlow<List<ChargeRecord>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val detectedDesignMah: Double? = settings.detectedDesignMah
    private val _manualDesignMah = MutableStateFlow(settings.manualDesignMah)
    val manualDesignMah: StateFlow<Double?> = _manualDesignMah.asStateFlow()

    fun start() { ChargeMonitorService.start(getApplication()) }
    fun stop() { ChargeMonitorService.stop(getApplication()) }

    fun setManualDesign(mah: Double?) {
        settings.manualDesignMah = mah
        _manualDesignMah.value = mah
    }

    fun delete(record: ChargeRecord) = viewModelScope.launch { dao.delete(record) }

    companion object {
        /** Moyenne des mesures fiables (confiance moyenne ou haute), null s'il n'y en a pas. */
        fun reliableAverage(records: List<ChargeRecord>): Double? =
            records.filter { it.confidence != Confidence.LOW.name }
                .map { it.bestMah }
                .takeIf { it.isNotEmpty() }
                ?.average()
    }
}

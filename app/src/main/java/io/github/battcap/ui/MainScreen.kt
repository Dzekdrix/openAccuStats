package io.github.battcap.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.battcap.BuildConfig
import io.github.battcap.R
import io.github.battcap.battery.BatterySample
import io.github.battcap.battery.CapacityEstimate
import io.github.battcap.battery.Confidence
import io.github.battcap.battery.Warning
import io.github.battcap.data.ChargeRecord
import io.github.battcap.service.ChargeMonitorService
import io.github.battcap.service.SessionOutcome
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val running by vm.running.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    val currentMa by vm.currentMa.collectAsStateWithLifecycle()
    val estimate by vm.estimate.collectAsStateWithLifecycle()
    val outcome by vm.lastOutcome.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val manual by vm.manualDesignMah.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // La notification est facultative : le service démarre même si la permission est refusée.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.start()
    }
    val onStart = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { LiveCard(sample, currentMa) }
            item {
                if (running) OutlinedButton(onClick = vm::stop, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_stop))
                } else Button(onClick = onStart, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_start))
                }
            }
            item { BatteryOptimizationCard() }
            item { EstimateCard(estimate, outcome, running, sample?.isCharging == true) }
            item {
                DesignCard(vm.detectedDesignMah, manual, onSave = vm::setManualDesign)
            }
            item {
                Text(
                    stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val avg = MainViewModel.reliableAverage(history)
                Text(
                    when {
                        avg != null -> stringResource(R.string.history_average, avg)
                        history.isNotEmpty() -> stringResource(R.string.history_no_reliable)
                        else -> stringResource(R.string.history_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(history, key = { it.id }) { RecordRow(it, onDelete = { vm.delete(it) }) }
            item {
                Text(
                    stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun LiveCard(s: BatterySample?, currentMa: Double?) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium)
        if (s == null) {
            Text(stringResource(R.string.live_no_data))
            return@Column
        }
        Text(
            stringResource(if (s.isCharging) R.string.status_charging else R.string.status_not_charging),
            color = MaterialTheme.colorScheme.primary
        )
        Metric(stringResource(R.string.live_level), "${s.levelPct} %")
        Metric(
            stringResource(R.string.live_current),
            when {
                s.currentRaw == null -> stringResource(R.string.current_unavailable)
                currentMa == null -> stringResource(R.string.calibrating)
                else -> stringResource(R.string.unit_ma_signed, currentMa)
            }
        )
        Metric(stringResource(R.string.live_voltage), stringResource(R.string.unit_v, s.voltageMv / 1000.0))
        Metric(stringResource(R.string.live_temp), stringResource(R.string.unit_c, s.temperatureC))
    }
}

@Composable
private fun EstimateCard(
    e: CapacityEstimate?,
    outcome: SessionOutcome?,
    running: Boolean,
    charging: Boolean
) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.estimate_title), style = MaterialTheme.typography.titleMedium)
        if (e == null) {
            Text(
                stringResource(
                    if (running && charging) R.string.estimate_waiting else R.string.hint_start
                )
            )
            if (!charging && outcome != null) {
                Text(
                    when (outcome) {
                        is SessionOutcome.Saved ->
                            stringResource(R.string.outcome_saved, outcome.mah, outcome.deltaSocPct)
                        is SessionOutcome.TooShort -> stringResource(
                            R.string.outcome_too_short, outcome.deltaSocPct,
                            ChargeMonitorService.MIN_SAVED_DELTA_SOC
                        )
                        SessionOutcome.NoMeasurement -> stringResource(R.string.outcome_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            return@Column
        }
        Text(
            stringResource(R.string.unit_mah, e.bestMah),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(stringResource(R.string.estimate_window, e.startLevel, e.endLevel))
        Text(stringResource(R.string.estimate_confidence, stringResource(e.confidence.label())))
        if (e.reachedFull) {
            Text("✓ " + stringResource(R.string.estimate_full), color = MaterialTheme.colorScheme.primary)
        }
        e.healthPct?.let { Text(stringResource(R.string.estimate_health, it)) }
        if (e.energyWh != null && e.meanVoltageV != null) {
            Text(stringResource(R.string.estimate_energy, e.energyWh, e.meanVoltageV))
        }
        Text(
            stringResource(
                R.string.estimate_methods,
                e.coulombMah?.let { stringResource(R.string.unit_mah, it) } ?: "—",
                e.chargeCounterMah?.let { stringResource(R.string.unit_mah, it) } ?: "—"
            ),
            style = MaterialTheme.typography.bodySmall
        )
        e.warnings.forEach {
            Text("⚠ " + stringResource(it.label()), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DesignCard(detected: Double?, manual: Double?, onSave: (Double?) -> Unit) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.design_title), style = MaterialTheme.typography.titleMedium)
        Text(
            detected?.let { stringResource(R.string.design_detected, it) }
                ?: stringResource(R.string.design_not_detected)
        )
        var text by remember(manual) { mutableStateOf(manual?.let { "%.0f".format(it) } ?: "") }
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
            label = { Text(stringResource(R.string.design_manual_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text.toDoubleOrNull()?.takeIf { it > 0 }) }) {
                Text(stringResource(R.string.btn_save))
            }
            TextButton(onClick = { text = ""; onSave(null) }) { Text(stringResource(R.string.btn_clear)) }
        }
    }
}

@Composable
private fun RecordRow(r: ChargeRecord, onDelete: () -> Unit) {
    val context = LocalContext.current
    val date = DateFormat.getMediumDateFormat(context).format(Date(r.endedAtEpochMs)) + " " +
            DateFormat.getTimeFormat(context).format(Date(r.endedAtEpochMs))
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.unit_mah, r.bestMah), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        R.string.history_item, r.startLevel, r.endLevel,
                        stringResource(runCatching { Confidence.valueOf(r.confidence) }
                            .getOrDefault(Confidence.LOW).label())
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (r.reachedFull) {
                    Text(
                        "✓ " + stringResource(R.string.history_full),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(date, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.btn_delete)) }
        }
    }
}

/**
 * Les surcouches constructeur (Xiaomi, Samsung, Huawei…) tuent souvent les services en arrière-plan
 * malgré la notification : la mesure en cours est alors perdue. On propose l'exemption.
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        ignoring = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (ignoring) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battopt_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.battopt_text), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                try {
                    context.startActivity(direct)
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text(stringResource(R.string.battopt_button)) }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f))
    Text(value, fontWeight = FontWeight.Medium)
}

private fun Confidence.label() = when (this) {
    Confidence.LOW -> R.string.confidence_low
    Confidence.MEDIUM -> R.string.confidence_medium
    Confidence.HIGH -> R.string.confidence_high
}

private fun Warning.label() = when (this) {
    Warning.SHORT_WINDOW -> R.string.warn_short_window
    Warning.GAPS -> R.string.warn_gaps
    Warning.HOT -> R.string.warn_hot
    Warning.METHODS_DISAGREE -> R.string.warn_disagree
    Warning.ABOVE_DESIGN -> R.string.warn_above_design
}

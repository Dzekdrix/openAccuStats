package io.github.battcap.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.battcap.BuildConfig
import io.github.battcap.R
import io.github.battcap.data.DiagnosticLog
import io.github.battcap.service.MonitoringController

private const val SHOWN_ENTRIES = 40

/**
 * État du suivi et journal des événements : permet de voir pourquoi une charge
 * n'a pas été mesurée (tâche non programmée, refus d'Android, service arrêté…).
 */
@Composable
fun DiagnosticCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var version by remember { mutableIntStateOf(0) } // incrémenté pour relire l'état
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose { }
    }
    val status = remember(version) { MonitoringController.status(context) }
    val entries = remember(version) { DiagnosticLog.entries(context) }
    val yes = stringResource(R.string.diag_yes)
    val no = stringResource(R.string.diag_no)
    val yn: (Boolean) -> String = { if (it) yes else no }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diag_help), style = MaterialTheme.typography.bodySmall)

            Metric(stringResource(R.string.diag_enabled), yn(status.enabled))
            Metric(stringResource(R.string.diag_exempt), yn(status.exempt))
            Metric(stringResource(R.string.diag_job_charging), yn(status.chargingJobPending))
            Metric(stringResource(R.string.diag_job_safety), yn(status.safetyJobPending))
            Metric(stringResource(R.string.diag_service), yn(status.serviceRunning))
            status.standbyBucket?.let { Metric(stringResource(R.string.diag_bucket), stringResource(bucketLabel(it))) }
            Text(status.device, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.diag_log_title), style = MaterialTheme.typography.titleSmall)
            if (entries.isEmpty()) {
                Text(stringResource(R.string.diag_log_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                entries.take(SHOWN_ENTRIES).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(fullReport(context, status, entries, yn)))
                }) { Text(stringResource(R.string.diag_copy)) }
                TextButton(onClick = { version++ }) { Text(stringResource(R.string.diag_refresh)) }
                TextButton(onClick = { DiagnosticLog.clear(context); version++ }) {
                    Text(stringResource(R.string.btn_clear))
                }
            }
        }
    }
}

/** Groupes de veille d'Android (UsageStatsManager.STANDBY_BUCKET_*). */
private fun bucketLabel(bucket: Int) = when {
    bucket <= 10 -> R.string.diag_bucket_active      // ACTIVE
    bucket <= 20 -> R.string.diag_bucket_working     // WORKING_SET
    bucket <= 30 -> R.string.diag_bucket_frequent    // FREQUENT
    bucket <= 40 -> R.string.diag_bucket_rare        // RARE
    else -> R.string.diag_bucket_restricted          // RESTRICTED (45) et au-delà
}

/** Texte complet à coller dans un message (état + tout le journal). */
private fun fullReport(
    context: Context,
    s: MonitoringController.Status,
    entries: List<String>,
    yn: (Boolean) -> String
): String = buildString {
    appendLine("BattCap ${BuildConfig.VERSION_NAME} — ${s.device}")
    appendLine("${context.getString(R.string.diag_enabled)} : ${yn(s.enabled)}")
    appendLine("${context.getString(R.string.diag_exempt)} : ${yn(s.exempt)}")
    appendLine("${context.getString(R.string.diag_job_charging)} : ${yn(s.chargingJobPending)}")
    appendLine("${context.getString(R.string.diag_job_safety)} : ${yn(s.safetyJobPending)}")
    appendLine("${context.getString(R.string.diag_service)} : ${yn(s.serviceRunning)}")
    s.standbyBucket?.let { appendLine("${context.getString(R.string.diag_bucket)} : ${context.getString(bucketLabel(it))} ($it)") }
    appendLine()
    entries.forEach { appendLine(it) }
}

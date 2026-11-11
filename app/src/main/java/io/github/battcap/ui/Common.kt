package io.github.battcap.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.battcap.R
import io.github.battcap.battery.Confidence
import io.github.battcap.battery.Warning
import java.util.Date

@Composable
fun Metric(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f))
    Text(value, fontWeight = FontWeight.Medium)
}

fun Confidence.label() = when (this) {
    Confidence.LOW -> R.string.confidence_low
    Confidence.MEDIUM -> R.string.confidence_medium
    Confidence.HIGH -> R.string.confidence_high
}

fun Warning.label() = when (this) {
    Warning.SHORT_WINDOW -> R.string.warn_short_window
    Warning.GAPS -> R.string.warn_gaps
    Warning.HOT -> R.string.warn_hot
    Warning.METHODS_DISAGREE -> R.string.warn_disagree
    Warning.ABOVE_DESIGN -> R.string.warn_above_design
}

/** Confiance stockée en texte dans Room ; valeur inconnue => faible. */
fun confidenceOf(name: String): Confidence =
    runCatching { Confidence.valueOf(name) }.getOrDefault(Confidence.LOW)

/**
 * Période d'une charge : « 28 oct. 2026, 22:14 - 23:51 » si elle tient dans la journée,
 * sinon « 28 oct. 2026 22:14 - 29 oct. 2026 07:02 ». Début inconnu (charges enregistrées
 * avant la v3 de la base) : seule la fin est indiquée.
 */
fun formatDateRange(context: Context, startMs: Long?, endMs: Long): String {
    if (startMs == null) return context.getString(R.string.history_ended_at, formatDateTime(context, endMs))
    val date = DateFormat.getMediumDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    val start = Date(startMs)
    val end = Date(endMs)
    return if (date.format(start) == date.format(end)) {
        context.getString(R.string.history_period_same_day, date.format(start), time.format(start), time.format(end))
    } else {
        context.getString(R.string.history_period, formatDateTime(context, startMs), formatDateTime(context, endMs))
    }
}

/** Date + heure au format de l'utilisateur (12/24 h, ordre jour/mois selon la langue). */
fun formatDateTime(context: Context, epochMs: Long): String {
    val d = Date(epochMs)
    return DateFormat.getMediumDateFormat(context).format(d) + " " + DateFormat.getTimeFormat(context).format(d)
}

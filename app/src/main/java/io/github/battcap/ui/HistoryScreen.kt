package io.github.battcap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.battcap.R
import io.github.battcap.battery.CapacityAverage
import io.github.battcap.data.ChargeRecord
import io.github.battcap.data.isPartial

private const val RECENT_COUNT = 10

/**
 * Historique des charges.
 * - Par défaut, les 10 dernières ; un bouton segmenté Material 3 (« 10 dernières | Tout (N) »)
 *   permet d'afficher tout l'historique. Le choix est conservé à la rotation.
 * - Charge complète ou suffisante : Card (carte pleine).
 *   Charge partielle (< 30 % de niveau, hors moyenne) : OutlinedCard (contour, sans fond),
 *   texte atténué et étiquette « Partielle ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, padding: PaddingValues) {
    val history by vm.history.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) history else history.take(RECENT_COUNT)

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { AverageCard(history) }

        if (history.size > RECENT_COUNT) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !showAll,
                        onClick = { showAll = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.history_recent, RECENT_COUNT)) }
                    SegmentedButton(
                        selected = showAll,
                        onClick = { showAll = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.history_all, history.size)) }
                }
            }
        }

        if (history.isEmpty()) {
            item { Text(stringResource(R.string.history_empty)) }
        }
        items(visible, key = { it.id }) { RecordRow(it, onDelete = { vm.delete(it) }) }
    }
}

@Composable
private fun AverageCard(history: List<ChargeRecord>) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val avg = MainViewModel.weightedAverage(history)
        Text(stringResource(R.string.history_average_title), style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                avg != null -> stringResource(R.string.unit_mah, avg)
                else -> "—"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (avg == null && history.isNotEmpty()) stringResource(R.string.history_no_reliable)
            else stringResource(R.string.history_average_help, CapacityAverage.MIN_DELTA_SOC),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RecordRow(r: ChargeRecord, onDelete: () -> Unit) {
    if (r.isPartial) {
        OutlinedCard(Modifier.fillMaxWidth()) { RecordContent(r, partial = true, onDelete) }
    } else {
        Card(Modifier.fillMaxWidth()) { RecordContent(r, partial = false, onDelete) }
    }
}

@Composable
private fun RecordContent(r: ChargeRecord, partial: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    // Texte atténué pour les charges partielles : présentes mais secondaires
    val secondary = if (partial) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.unit_mah, r.bestMah),
                    fontWeight = if (partial) FontWeight.Medium else FontWeight.Bold,
                    color = secondary
                )
                if (partial) PartialTag()
            }
            Text(
                stringResource(
                    R.string.history_item, r.startLevel, r.endLevel,
                    stringResource(confidenceOf(r.confidence).label())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = secondary
            )
            if (partial) {
                Text(
                    stringResource(R.string.history_partial_note, CapacityAverage.MIN_DELTA_SOC),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
            }
            if (r.reachedFull) {
                Text(
                    "✓ " + stringResource(R.string.history_full),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(formatDateTime(context, r.endedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.btn_delete)) }
    }
}

/** Étiquette statique (Surface tonale) : Material 3 n'a pas de composant « tag » non cliquable. */
@Composable
private fun PartialTag() = Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Text(
        stringResource(R.string.history_partial_tag),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

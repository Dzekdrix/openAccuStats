package io.github.battcap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.battcap.R
import io.github.battcap.data.isPartial
import io.github.battcap.ui.chart.CapacityChart
import io.github.battcap.ui.chart.ChartPoint

@Composable
fun StatsScreen(vm: MainViewModel, padding: PaddingValues) {
    val history by vm.history.collectAsStateWithLifecycle()
    val manual by vm.manualDesignMah.collectAsStateWithLifecycle()
    val designMah = manual ?: vm.detectedDesignMah

    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stats_capacity_title), style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) {
                    Text(stringResource(R.string.stats_empty))
                    return@Column
                }

                val points = remember(history) {
                    history.map {
                        ChartPoint(it.endedAtEpochMs, it.bestMah, reliable = !it.isPartial, id = it.id)
                    }
                }
                var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

                CapacityChart(
                    points = points,
                    designMah = designMah,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    description = stringResource(R.string.stats_chart_description, points.size)
                )
                Legend(showDesign = designMah != null)

                // Détail du point touché, sinon invitation à toucher
                val selected = history.firstOrNull { it.id == selectedId }
                if (selected != null) {
                    val context = LocalContext.current
                    Text(
                        stringResource(R.string.unit_mah, selected.bestMah),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        formatDateTime(context, selected.endedAtEpochMs) + " · " +
                            stringResource(
                                R.string.history_item, selected.startLevel, selected.endLevel,
                                stringResource(confidenceOf(selected.confidence).label())
                            ),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(stringResource(R.string.stats_tap_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (history.isNotEmpty()) SummaryCard(history.size, MainViewModel.weightedAverage(history), designMah)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(showDesign: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val style = MaterialTheme.typography.labelSmall
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem("●", primary, stringResource(R.string.stats_legend_reliable), style)
        LegendItem("○", primary, stringResource(R.string.stats_legend_low), style)
        if (showDesign) {
            LegendItem("╌╌", MaterialTheme.colorScheme.tertiary, stringResource(R.string.stats_legend_design), style)
        }
    }
}

@Composable
private fun LegendItem(symbol: String, color: Color, label: String, style: TextStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(symbol, color = color, style = style)
        Text(label, style = style)
    }
}

@Composable
private fun SummaryCard(count: Int, reliableAvg: Double?, designMah: Double?) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Metric(stringResource(R.string.stats_count), count.toString())
        Metric(
            stringResource(R.string.stats_average),
            reliableAvg?.let { stringResource(R.string.unit_mah, it) } ?: "—"
        )
        if (reliableAvg != null && designMah != null) {
            Metric(stringResource(R.string.stats_health), "%.0f %%".format(reliableAvg / designMah * 100))
        }
    }
}

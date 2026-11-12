/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.rangetest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_band_strong
import org.meshtastic.core.resources.range_test_band_very_weak
import org.meshtastic.core.resources.range_test_by_distance
import org.meshtastic.core.resources.range_test_export_csv
import org.meshtastic.core.resources.range_test_export_done
import org.meshtastic.core.resources.range_test_export_failed
import org.meshtastic.core.resources.range_test_export_kml
import org.meshtastic.core.resources.range_test_include_relayed
import org.meshtastic.core.resources.range_test_legend_title
import org.meshtastic.core.resources.range_test_map_unavailable
import org.meshtastic.core.resources.range_test_max_distance
import org.meshtastic.core.resources.range_test_missed
import org.meshtastic.core.resources.range_test_received
import org.meshtastic.core.resources.range_test_relayed
import org.meshtastic.core.resources.range_test_route
import org.meshtastic.core.resources.range_test_success_rate
import org.meshtastic.core.resources.range_test_you
import org.meshtastic.core.resources.rssi
import org.meshtastic.core.resources.snr
import org.meshtastic.core.ui.icon.FileDownload
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.core.ui.util.LocalRangeTestMapProvider
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.rememberSaveFileLauncher
import org.meshtastic.feature.rangetest.RangeTestExportResult
import org.meshtastic.feature.rangetest.RangeTestHeatmap
import org.meshtastic.feature.rangetest.RangeTestMapSettings
import org.meshtastic.feature.rangetest.RangeTestMapViewModel
import org.meshtastic.feature.rangetest.RangeTestMetric
import org.meshtastic.feature.rangetest.RangeTestSummary

private const val PERCENT = 100f
private const val LEGEND_STEPS = 12
private val PANEL_MAX_HEIGHT = 300.dp
private val SIDE_PANEL_WIDTH = 360.dp
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp

/**
 * The session map: a signal-strength heatmap over the base map, with the controls, colour legend and summary in a panel
 * below (phones) or beside it (wide windows).
 */
@Composable
fun RangeTestMapScreen(viewModel: RangeTestMapViewModel, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val you = stringResource(Res.string.range_test_you)

    KeepScreenOn(enabled = state.live != null)
    ExportResultSnackbar(viewModel.exportResults, snackbar)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackTopBar(title = state.session?.targetName.orEmpty(), onNavigateUp = onNavigateUp) {
                ExportMenu(
                    baseName = "rangetest-${state.session?.id ?: 0}",
                    onCsv = viewModel::exportCsv,
                    onKml = viewModel::exportKml,
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val mapData = state.mapData.copy(receiver = state.mapData.receiver?.copy(label = you))
        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            Column(
                panelModifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Controls(
                    settings = state.settings,
                    onMetric = viewModel::setMetric,
                    onIncludeRelayedChange = viewModel::setIncludeRelayed,
                    onShowRouteChange = viewModel::setShowRoute,
                )
                Legend()
                HorizontalDivider()
                SummaryBlock(state.summary, units)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                Row(Modifier.fillMaxSize()) {
                    MapArea(mapData, Modifier.weight(1f).fillMaxHeight())
                    panel(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    MapArea(mapData, Modifier.weight(1f).fillMaxWidth())
                    panel(Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT))
                }
            }
        }
    }
}

@Composable
private fun ExportResultSnackbar(results: Flow<RangeTestExportResult>, snackbar: SnackbarHostState) {
    LaunchedEffect(results) {
        results.collect { result ->
            snackbar.showSnackbar(
                getString(
                    if (result == RangeTestExportResult.SUCCESS) {
                        Res.string.range_test_export_done
                    } else {
                        Res.string.range_test_export_failed
                    },
                ),
            )
        }
    }
}

@Composable
private fun ExportMenu(baseName: String, onCsv: (CommonUri) -> Unit, onKml: (CommonUri) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val saveCsv = rememberSaveFileLauncher(onCsv)
    val saveKml = rememberSaveFileLauncher(onKml)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(MeshtasticIcons.FileDownload, contentDescription = stringResource(Res.string.range_test_export_csv))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.range_test_export_csv)) },
                onClick = {
                    expanded = false
                    saveCsv("$baseName.csv", "text/csv")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.range_test_export_kml)) },
                onClick = {
                    expanded = false
                    saveKml("$baseName.kml", "application/vnd.google-earth.kml+xml")
                },
            )
        }
    }
}

@Composable
private fun MapArea(data: RangeTestMapData, modifier: Modifier = Modifier) {
    val rangeTestMap = LocalRangeTestMapProvider.current
    Box(modifier) {
        if (data.boundsPoints.isEmpty()) {
            Text(
                stringResource(Res.string.range_test_map_unavailable),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else {
            rangeTestMap(data, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    settings: RangeTestMapSettings,
    onMetric: (RangeTestMetric) -> Unit,
    onIncludeRelayedChange: (Boolean) -> Unit,
    onShowRouteChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val metrics = listOf(RangeTestMetric.SNR to Res.string.snr, RangeTestMetric.RSSI to Res.string.rssi)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            metrics.forEachIndexed { index, (metric, label) ->
                SegmentedButton(
                    selected = settings.metric == metric,
                    onClick = { onMetric(metric) },
                    shape = SegmentedButtonDefaults.itemShape(index, metrics.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.showRoute,
                onClick = { onShowRouteChange(!settings.showRoute) },
                label = { Text(stringResource(Res.string.range_test_route)) },
            )
            FilterChip(
                selected = settings.includeRelayed,
                onClick = { onIncludeRelayedChange(!settings.includeRelayed) },
                label = { Text(stringResource(Res.string.range_test_include_relayed)) },
            )
        }
    }
}

/** The heatmap's colour scale, weak to strong, plus the grey used where beacons went missing. */
@Composable
private fun Legend() {
    val colors = remember {
        List(LEGEND_STEPS + 1) { step -> Color(RangeTestHeatmap.colorAt(step.toFloat() / LEGEND_STEPS)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.range_test_legend_title), style = MaterialTheme.typography.titleSmall)
        val barShape = RoundedCornerShape(6.dp)
        Box(Modifier.fillMaxWidth().height(12.dp).background(Brush.horizontalGradient(colors), barShape))
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.range_test_band_very_weak), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.range_test_band_strong), style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.size(width = 24.dp, height = 12.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(MISSED_SWATCH.toInt()),
            ) {}
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.range_test_missed), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val MISSED_SWATCH = 0xFF9E9E9E

@Composable
private fun SummaryBlock(summary: RangeTestSummary, units: MeasurementSystem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow(
            buildList {
                add(stringResource(Res.string.range_test_received) to summary.received.toString())
                add(stringResource(Res.string.range_test_missed) to summary.missed.toString())
                if (summary.relayed > 0) {
                    add(stringResource(Res.string.range_test_relayed) to summary.relayed.toString())
                }
                add(stringResource(Res.string.range_test_success_rate) to "${(summary.successRate * PERCENT).toInt()}%")
            },
        )
        summary.maxDirectDistanceM?.let {
            Text("${stringResource(Res.string.range_test_max_distance)}: ${formatDistance(it, units)}")
        }
        val averages =
            listOfNotNull(
                summary.avgSnr?.let { "${stringResource(Res.string.snr)} ${formatSnr(it)}" },
                summary.avgRssi?.let { "${stringResource(Res.string.rssi)} ${formatRssi(it)}" },
            )
        if (averages.isNotEmpty()) Text(averages.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (summary.buckets.isNotEmpty()) {
            Text(stringResource(Res.string.range_test_by_distance), style = MaterialTheme.typography.titleSmall)
            summary.buckets.forEach { bucket ->
                val total = bucket.received + bucket.missed
                val range =
                    formatDistance(bucket.fromM.toDouble(), units) +
                        " – " +
                        (bucket.toM?.let { formatDistance(it.toDouble(), units) } ?: "∞")
                Text(
                    "$range: ${bucket.received}/$total (${(bucket.received * PERCENT / total).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

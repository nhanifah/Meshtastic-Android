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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_avg_min
import org.meshtastic.core.resources.range_test_band_fair
import org.meshtastic.core.resources.range_test_band_good
import org.meshtastic.core.resources.range_test_band_strong
import org.meshtastic.core.resources.range_test_band_very_weak
import org.meshtastic.core.resources.range_test_band_weak
import org.meshtastic.core.resources.range_test_by_distance
import org.meshtastic.core.resources.range_test_cell_counts
import org.meshtastic.core.resources.range_test_cell_size
import org.meshtastic.core.resources.range_test_export_csv
import org.meshtastic.core.resources.range_test_export_done
import org.meshtastic.core.resources.range_test_export_failed
import org.meshtastic.core.resources.range_test_export_kml
import org.meshtastic.core.resources.range_test_heat_hint
import org.meshtastic.core.resources.range_test_include_relayed
import org.meshtastic.core.resources.range_test_map_unavailable
import org.meshtastic.core.resources.range_test_max_distance
import org.meshtastic.core.resources.range_test_missed
import org.meshtastic.core.resources.range_test_mode_grid
import org.meshtastic.core.resources.range_test_mode_heatmap
import org.meshtastic.core.resources.range_test_mode_points
import org.meshtastic.core.resources.range_test_relayed
import org.meshtastic.core.resources.range_test_success_rate
import org.meshtastic.core.resources.rssi
import org.meshtastic.core.resources.snr
import org.meshtastic.core.ui.icon.FileDownload
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.core.ui.util.LocalRangeTestMapProvider
import org.meshtastic.core.ui.util.RangeTestMapMode
import org.meshtastic.core.ui.util.rememberSaveFileLauncher
import org.meshtastic.feature.rangetest.RangeTestCellStats
import org.meshtastic.feature.rangetest.RangeTestExportResult
import org.meshtastic.feature.rangetest.RangeTestMapSettings
import org.meshtastic.feature.rangetest.RangeTestMapUiState
import org.meshtastic.feature.rangetest.RangeTestMapViewModel
import org.meshtastic.feature.rangetest.RangeTestMetric
import org.meshtastic.feature.rangetest.RangeTestSummary
import org.meshtastic.feature.rangetest.SignalBand

private const val PERCENT = 100f
private val CONTROLS_MAX_HEIGHT = 280.dp

@Composable
fun RangeTestMapScreen(viewModel: RangeTestMapViewModel, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            MapArea(state, onCellClick = viewModel::selectCell, modifier = Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = CONTROLS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Controls(
                    settings = state.settings,
                    onMode = viewModel::setMode,
                    onMetric = viewModel::setMetric,
                    onCellSize = viewModel::setCellSize,
                    onIncludeRelayedChange = viewModel::setIncludeRelayed,
                )
                state.selectedCell?.let { CellDetails(it, units) }
                SummaryBlock(state.summary, units)
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
private fun MapArea(state: RangeTestMapUiState, onCellClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val rangeTestMap = LocalRangeTestMapProvider.current
    Box(modifier) {
        if (state.mapData.boundsPoints.isEmpty()) {
            Text(
                stringResource(Res.string.range_test_map_unavailable),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else {
            rangeTestMap(state.mapData, onCellClick, Modifier.fillMaxSize())
        }
        Legend(mode = state.settings.mode, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

@Composable
private fun Controls(
    settings: RangeTestMapSettings,
    onMode: (RangeTestMapMode) -> Unit,
    onMetric: (RangeTestMetric) -> Unit,
    onCellSize: (Int) -> Unit,
    onIncludeRelayedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val modes =
            listOf(
                RangeTestMapMode.GRID to Res.string.range_test_mode_grid,
                RangeTestMapMode.HEATMAP to Res.string.range_test_mode_heatmap,
                RangeTestMapMode.POINTS to Res.string.range_test_mode_points,
            )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.metric == RangeTestMetric.SNR,
                onClick = { onMetric(RangeTestMetric.SNR) },
                label = { Text(stringResource(Res.string.snr)) },
            )
            FilterChip(
                selected = settings.metric == RangeTestMetric.RSSI,
                onClick = { onMetric(RangeTestMetric.RSSI) },
                label = { Text(stringResource(Res.string.rssi)) },
            )
            FilterChip(
                selected = settings.includeRelayed,
                onClick = { onIncludeRelayedChange(!settings.includeRelayed) },
                label = { Text(stringResource(Res.string.range_test_include_relayed)) },
            )
        }
        if (settings.mode == RangeTestMapMode.GRID) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeTestMapSettings.CELL_SIZES_M.forEach { size ->
                    FilterChip(
                        selected = settings.cellSizeM == size,
                        onClick = { onCellSize(size) },
                        label = { Text(stringResource(Res.string.range_test_cell_size, size)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend(mode: RangeTestMapMode, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mode == RangeTestMapMode.HEATMAP) {
                Text(stringResource(Res.string.range_test_heat_hint), style = MaterialTheme.typography.labelSmall)
            } else {
                listOf(
                    SignalBand.STRONG to Res.string.range_test_band_strong,
                    SignalBand.GOOD to Res.string.range_test_band_good,
                    SignalBand.FAIR to Res.string.range_test_band_fair,
                    SignalBand.WEAK to Res.string.range_test_band_weak,
                    SignalBand.VERY_WEAK to Res.string.range_test_band_very_weak,
                    SignalBand.MISSED to Res.string.range_test_missed,
                )
                    .forEach { (band, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(band.argb), CircleShape))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                        }
                    }
            }
        }
    }
}

@Composable
private fun CellDetails(cell: RangeTestCellStats, units: MeasurementSystem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.range_test_cell_counts, cell.received, cell.missed))
            Text("${stringResource(Res.string.range_test_success_rate)}: ${(cell.successRate * PERCENT).toInt()}%")
            if (cell.avgSnr != null && cell.minSnr != null) {
                Text(
                    "${stringResource(Res.string.snr)}: " +
                        stringResource(Res.string.range_test_avg_min, formatSnr(cell.avgSnr), formatSnr(cell.minSnr)),
                )
            }
            if (cell.avgRssi != null && cell.minRssi != null) {
                Text(
                    "${stringResource(Res.string.rssi)}: " +
                        stringResource(
                            Res.string.range_test_avg_min,
                            formatRssi(cell.avgRssi),
                            formatRssi(cell.minRssi),
                        ),
                )
            }
            cell.avgDistanceM?.let { Text(formatDistance(it, units)) }
        }
    }
}

@Composable
private fun SummaryBlock(summary: RangeTestSummary, units: MeasurementSystem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val counts = stringResource(Res.string.range_test_cell_counts, summary.received, summary.missed)
        val rate = "${stringResource(Res.string.range_test_success_rate)} ${(summary.successRate * PERCENT).toInt()}%"
        val relayedLabel = stringResource(Res.string.range_test_relayed)
        val relayed = if (summary.relayed > 0) " · $relayedLabel: ${summary.relayed}" else ""
        Text("$counts · $rate$relayed", style = MaterialTheme.typography.bodyMedium)
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

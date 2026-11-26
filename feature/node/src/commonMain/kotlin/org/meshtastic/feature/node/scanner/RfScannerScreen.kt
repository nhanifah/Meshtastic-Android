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
package org.meshtastic.feature.node.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.node_sort_last_heard
import org.meshtastic.core.resources.rf_scanner_active_scan
import org.meshtastic.core.resources.rf_scanner_active_scan_summary
import org.meshtastic.core.resources.rf_scanner_direct
import org.meshtastic.core.resources.rf_scanner_empty
import org.meshtastic.core.resources.rf_scanner_excluded_network
import org.meshtastic.core.resources.rf_scanner_explainer
import org.meshtastic.core.resources.rf_scanner_hops
import org.meshtastic.core.resources.rf_scanner_relayed
import org.meshtastic.core.resources.rf_scanner_sort
import org.meshtastic.core.resources.rf_scanner_sort_signal
import org.meshtastic.core.resources.rf_scanner_title
import org.meshtastic.core.resources.rf_scanner_total
import org.meshtastic.core.resources.rf_scanner_unknown_hops
import org.meshtastic.core.resources.rf_scanner_window
import org.meshtastic.core.resources.rf_scanner_window_15m
import org.meshtastic.core.resources.rf_scanner_window_1h
import org.meshtastic.core.resources.rf_scanner_window_24h
import org.meshtastic.core.resources.rf_scanner_window_2h
import org.meshtastic.core.resources.rf_scanner_window_6h
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.NodeChip
import org.meshtastic.core.ui.component.SignalInfo
import org.meshtastic.core.ui.util.formatAgo

private val RfScanWindow.label: StringResource
    get() =
        when (this) {
            RfScanWindow.MINUTES_15 -> Res.string.rf_scanner_window_15m
            RfScanWindow.HOUR_1 -> Res.string.rf_scanner_window_1h
            RfScanWindow.HOURS_2 -> Res.string.rf_scanner_window_2h
            RfScanWindow.HOURS_6 -> Res.string.rf_scanner_window_6h
            RfScanWindow.HOURS_24 -> Res.string.rf_scanner_window_24h
        }

private val RfScanSort.label: StringResource
    get() =
        when (this) {
            RfScanSort.SIGNAL -> Res.string.rf_scanner_sort_signal
            RfScanSort.LAST_HEARD -> Res.string.node_sort_last_heard
        }

@Composable
fun RfScannerScreen(
    viewModel: RfScannerViewModel,
    onNavigateUp: () -> Unit,
    onNodeClick: (Int) -> Unit,
    onOpenActiveScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.rf_scanner_title),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                onClickChip = {},
                actions = {},
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") { RfSummaryCard(state.result) }
            item(key = "window") {
                ChoiceRow(
                    title = stringResource(Res.string.rf_scanner_window),
                    options = RfScanWindow.entries,
                    selected = state.window,
                    label = { it.label },
                    onSelect = viewModel::setWindow,
                )
            }
            item(key = "sort") {
                ChoiceRow(
                    title = stringResource(Res.string.rf_scanner_sort),
                    options = RfScanSort.entries,
                    selected = state.sort,
                    label = { it.label },
                    onSelect = viewModel::setSort,
                )
            }
            item(key = "explainer") {
                Text(
                    text = stringResource(Res.string.rf_scanner_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "active") { ActiveScanCard(onOpenActiveScan) }
            if (state.result.entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.rf_scanner_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            } else {
                items(state.result.entries, key = { it.node.num }) { entry ->
                    RfNodeRow(entry = entry, onClick = { onNodeClick(entry.node.num) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RfSummaryCard(result: RfScanResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(Res.string.rf_scanner_total), style = MaterialTheme.typography.titleSmall)
            Text(text = result.total.toString(), style = MaterialTheme.typography.displayMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CountCell(stringResource(Res.string.rf_scanner_direct), result.directCount)
                CountCell(stringResource(Res.string.rf_scanner_relayed), result.relayedCount)
                CountCell(stringResource(Res.string.rf_scanner_unknown_hops), result.unknownHopsCount)
            }
            Text(
                text = stringResource(Res.string.rf_scanner_excluded_network, result.excludedNetworkCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CountCell(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Suppress("LambdaParameterEventTrailing")
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> StringResource,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    onClick = { onSelect(option) },
                    selected = option == selected,
                    label = {
                        Text(text = stringResource(label(option)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveScanCard(onOpenActiveScan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.rf_scanner_active_scan_summary),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenActiveScan) { Text(stringResource(Res.string.rf_scanner_active_scan)) }
        }
    }
}

@Composable
private fun RfNodeRow(entry: RfScanEntry, onClick: () -> Unit) {
    val node = entry.node
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NodeChip(node = node)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = node.effectiveLongName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val linkText =
                when (entry.link) {
                    RfLink.DIRECT -> stringResource(Res.string.rf_scanner_direct)
                    RfLink.RELAYED -> stringResource(Res.string.rf_scanner_hops, node.hopsAway)
                    RfLink.UNKNOWN_HOPS -> stringResource(Res.string.rf_scanner_unknown_hops)
                }
            Text(
                text = "$linkText · ${formatAgo(node.lastHeard)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A relayed node's stored signal belongs to the relay, so only direct nodes show one.
            if (entry.link == RfLink.DIRECT) SignalInfo(node = node)
        }
    }
}

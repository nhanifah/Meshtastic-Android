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
package org.meshtastic.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.channel
import org.meshtastic.core.resources.range_test
import org.meshtastic.core.resources.range_test_acked
import org.meshtastic.core.resources.range_test_interval
import org.meshtastic.core.resources.range_test_missed
import org.meshtastic.core.resources.range_test_mode
import org.meshtastic.core.resources.range_test_mode_direct
import org.meshtastic.core.resources.range_test_mode_message
import org.meshtastic.core.resources.range_test_received
import org.meshtastic.core.resources.range_test_receiver_title
import org.meshtastic.core.resources.range_test_sender_title
import org.meshtastic.core.resources.range_test_sent
import org.meshtastic.core.resources.range_test_start_test
import org.meshtastic.core.resources.range_test_stop
import org.meshtastic.core.resources.range_test_stop_sending
import org.meshtastic.core.resources.range_test_waiting_beacon
import org.meshtastic.core.resources.wear_aim_hint
import org.meshtastic.core.resources.wear_beacon_every
import org.meshtastic.core.resources.wear_record_from
import org.meshtastic.core.resources.wear_send_beacons
import org.meshtastic.feature.rangetest.RangeTestBeaconMode
import org.meshtastic.feature.rangetest.RangeTestMetric
import org.meshtastic.feature.rangetest.RangeTestSenderConfig
import org.meshtastic.feature.rangetest.RangeTestSignal

@Composable
fun RangeTestHomeScreen(onSend: () -> Unit, onRecord: () -> Unit, onLiveRecording: () -> Unit) {
    val viewModel: WatchRangeTestViewModel = koinViewModel()
    val sender by viewModel.senderState.collectAsStateWithLifecycle()
    val recorder by viewModel.recorderState.collectAsStateWithLifecycle()
    ListScreen(title = stringResource(Res.string.range_test)) {
        item {
            RowButton(
                label =
                stringResource(
                    if (sender.isSending) Res.string.range_test_sender_title else Res.string.wear_send_beacons,
                ),
                secondary =
                if (sender.isSending) {
                    "${stringResource(Res.string.range_test_sent)} ${sender.sent}"
                } else {
                    stringResource(Res.string.wear_beacon_every, RangeTestSenderConfig.MIN_INTERVAL_SEC)
                },
                selected = sender.isSending,
                onClick = onSend,
            )
        }
        item {
            RowButton(
                label =
                if (recorder.isRecording) {
                    stringResource(Res.string.range_test_receiver_title)
                } else {
                    stringResource(Res.string.wear_record_from)
                },
                secondary = recorder.targetName,
                selected = recorder.isRecording,
                onClick = if (recorder.isRecording) onLiveRecording else onRecord,
            )
        }
    }
}

@Composable
fun RangeTestSendScreen() {
    val viewModel: WatchRangeTestViewModel = koinViewModel()
    val sender by viewModel.senderState.collectAsStateWithLifecycle()
    if (sender.isSending || (sender.config != null && sender.finished)) {
        SenderLive(viewModel)
    } else {
        SenderForm(viewModel)
    }
}

@Composable
private fun SenderForm(viewModel: WatchRangeTestViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    ListScreen(title = stringResource(Res.string.wear_send_beacons)) {
        item { InfoText(stringResource(Res.string.range_test_interval)) }
        RangeTestSenderConfig.INTERVALS_SEC.forEach { seconds ->
            item(key = "interval$seconds") {
                RowButton(
                    label = stringResource(Res.string.wear_beacon_every, seconds),
                    selected = draft.intervalSec == seconds,
                    onClick = { viewModel.setInterval(seconds) },
                )
            }
        }
        item { InfoText(stringResource(Res.string.range_test_mode)) }
        item {
            RowButton(
                label = stringResource(Res.string.range_test_mode_direct),
                selected = draft.mode == RangeTestBeaconMode.DIRECT,
                onClick = { viewModel.setMode(RangeTestBeaconMode.DIRECT) },
            )
        }
        item {
            RowButton(
                label = stringResource(Res.string.range_test_mode_message),
                selected = draft.mode == RangeTestBeaconMode.MESSAGE,
                onClick = { viewModel.setMode(RangeTestBeaconMode.MESSAGE) },
            )
        }
        if (channels.size > 1) {
            item { InfoText(stringResource(Res.string.channel)) }
            channels.forEach { channel ->
                item(key = "channel${channel.index}") {
                    RowButton(
                        label = channel.name,
                        selected = draft.channelIndex == channel.index,
                        onClick = { viewModel.setChannel(channel.index) },
                    )
                }
            }
        }
        item {
            RowButton(
                label = stringResource(Res.string.range_test_start_test),
                selected = true,
                onClick = viewModel::startSending,
            )
        }
    }
}

@Composable
private fun SenderLive(viewModel: WatchRangeTestViewModel) {
    val sender by viewModel.senderState.collectAsStateWithLifecycle()
    KeepScreenOn()
    val scroll = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = scroll,
        edgeButton = {
            EdgeButton(onClick = if (sender.isSending) viewModel::stopSending else viewModel::dismissSender) {
                Text(
                    stringResource(
                        if (sender.isSending) Res.string.range_test_stop_sending else Res.string.range_test_start_test,
                    ),
                )
            }
        },
    ) { contentPadding ->
        BigReadout(
            scroll = scroll,
            contentPadding = contentPadding,
            title = stringResource(Res.string.range_test_sender_title),
            value = sender.sent.toString(),
            valueColor = MaterialTheme.colorScheme.primary,
            lines =
            listOf(
                "${stringResource(Res.string.range_test_acked)} ${sender.acked}",
                sender.config?.let { stringResource(Res.string.wear_beacon_every, it.intervalSec) }.orEmpty(),
            ),
        )
    }
}

@Composable
fun RangeTestPickScreen(onPick: () -> Unit) {
    val nodesViewModel: NodesViewModel = koinViewModel()
    val viewModel: WatchRangeTestViewModel = koinViewModel()
    val state by nodesViewModel.state.collectAsStateWithLifecycle()
    ListScreen(title = stringResource(Res.string.wear_record_from)) {
        state.nodes.forEach { node ->
            item(key = node.num) {
                RowButton(
                    label = node.effectiveLongName.ifBlank { node.user.short_name },
                    secondary = nodeSummary(node, state.here, state.units),
                    onClick = {
                        viewModel.startRecording(node.num)
                        onPick()
                    },
                )
            }
        }
    }
}

/**
 * The receiving side, made for aiming a directional antenna: the last SNR fills the screen, coloured by signal band,
 * and the watch taps the wrist on every beacon heard.
 */
@Composable
fun RangeTestRecordScreen(modifier: Modifier = Modifier) {
    val viewModel: WatchRangeTestViewModel = koinViewModel()
    val recorder by viewModel.recorderState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(recorder.received) {
        if (recorder.received > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    KeepScreenOn()
    val snr = recorder.lastSnr
    val scroll = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = scroll,
        modifier = modifier,
        edgeButton = {
            if (recorder.isRecording) {
                EdgeButton(onClick = viewModel::stopRecording) { Text(stringResource(Res.string.range_test_stop)) }
            }
        },
    ) { contentPadding ->
        BigReadout(
            scroll = scroll,
            contentPadding = contentPadding,
            title = recorder.targetName.orEmpty(),
            value = snr?.let { "${oneDecimal(it)} dB" } ?: "—",
            valueColor =
            snr?.let { Color(RangeTestSignal.band(RangeTestMetric.SNR, it).argb) }
                ?: MaterialTheme.colorScheme.onSurface,
            lines =
            listOf(
                if (snr == null) {
                    stringResource(Res.string.range_test_waiting_beacon)
                } else {
                    listOfNotNull(
                        recorder.lastRssi?.let { "RSSI $it" },
                        recorder.lastHeardAt?.let { agoShort(it) },
                    )
                        .joinToString(" · ")
                },
                "${stringResource(Res.string.range_test_received)} ${recorder.received} · " +
                    "${stringResource(Res.string.range_test_missed)} ${recorder.missed}",
                stringResource(Res.string.wear_aim_hint),
            ),
        )
    }
}

@Composable
private fun BigReadout(
    scroll: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    title: String,
    value: String,
    valueColor: Color,
    lines: List<String>,
) {
    TransformingLazyColumn(state = scroll, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        item {
            Text(
                value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displayLarge,
                color = valueColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        lines
            .filter { it.isNotBlank() }
            .forEach { line ->
                item {
                    Text(
                        line,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
    }
}

/** A live range test is read at a glance while walking; the screen must not time out. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_acked
import org.meshtastic.core.resources.range_test_done
import org.meshtastic.core.resources.range_test_failed
import org.meshtastic.core.resources.range_test_keep_connected
import org.meshtastic.core.resources.range_test_last_beacon
import org.meshtastic.core.resources.range_test_last_sent
import org.meshtastic.core.resources.range_test_max_distance
import org.meshtastic.core.resources.range_test_missed
import org.meshtastic.core.resources.range_test_open_map
import org.meshtastic.core.resources.range_test_received
import org.meshtastic.core.resources.range_test_receiver_title
import org.meshtastic.core.resources.range_test_recording_stopped
import org.meshtastic.core.resources.range_test_recording_to
import org.meshtastic.core.resources.range_test_relayed
import org.meshtastic.core.resources.range_test_remove_channel
import org.meshtastic.core.resources.range_test_remove_channel_desc
import org.meshtastic.core.resources.range_test_seconds
import org.meshtastic.core.resources.range_test_seconds_ago
import org.meshtastic.core.resources.range_test_sender_title
import org.meshtastic.core.resources.range_test_sending_done
import org.meshtastic.core.resources.range_test_sending_status
import org.meshtastic.core.resources.range_test_sent
import org.meshtastic.core.resources.range_test_stop
import org.meshtastic.core.resources.range_test_stop_sending
import org.meshtastic.core.resources.range_test_success_rate
import org.meshtastic.core.resources.range_test_summary_interval
import org.meshtastic.core.resources.range_test_summary_target
import org.meshtastic.core.resources.range_test_target_all
import org.meshtastic.core.resources.range_test_time_left
import org.meshtastic.core.resources.range_test_unconfirmed
import org.meshtastic.core.resources.range_test_unplaced
import org.meshtastic.core.resources.range_test_waiting_beacon
import org.meshtastic.core.ui.icon.CellTower
import org.meshtastic.core.ui.icon.Channel
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.NearMe
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.feature.rangetest.RangeTestBeaconMode
import org.meshtastic.feature.rangetest.RangeTestChannelOption
import org.meshtastic.feature.rangetest.RangeTestRecorderState
import org.meshtastic.feature.rangetest.RangeTestSenderState
import org.meshtastic.feature.rangetest.RangeTestViewModel

private val LIVE_MAX_WIDTH = 640.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60
private const val PERCENT = 100f

/** The stationary side while it sends: counters, countdown, stop, and the offer to remove the test channel after. */
@Composable
fun RangeTestSenderScreen(viewModel: RangeTestViewModel, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.senderState.collectAsStateWithLifecycle()
    val channel by viewModel.senderChannel.collectAsStateWithLifecycle()
    KeepScreenOn(enabled = state.isSending)

    LiveScaffold(stringResource(Res.string.range_test_sender_title), onNavigateUp, modifier) {
        SenderStatus(state)
        if (state.isSending) {
            Text(stringResource(Res.string.range_test_keep_connected), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = viewModel::stopSending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.range_test_stop_sending))
            }
        } else {
            channel?.let { RemoveChannelCard(it, onRemove = { viewModel.removeChannel(it.index) }) }
            OutlinedButton(
                onClick = {
                    viewModel.dismissSender()
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.range_test_done))
            }
        }
    }
}

@Composable
private fun SenderStatus(state: RangeTestSenderState) {
    val config = state.config ?: return
    val now by rememberTickingNow()
    InfoCard(
        icon = MeshtasticIcons.CellTower,
        title =
        if (state.isSending) {
            stringResource(Res.string.range_test_sending_status, config.channelName)
        } else {
            stringResource(Res.string.range_test_sending_done)
        },
        highlighted = state.isSending,
    ) {
        StatRow(
            buildList {
                add(stringResource(Res.string.range_test_sent) to state.sent.toString())
                if (config.mode == RangeTestBeaconMode.MESSAGE) {
                    add(stringResource(Res.string.range_test_acked) to state.acked.toString())
                    add(stringResource(Res.string.range_test_unconfirmed) to state.unconfirmed.toString())
                }
                add(stringResource(Res.string.range_test_failed) to state.failed.toString())
            },
        )
        Text(
            "${stringResource(Res.string.range_test_summary_target)}: " +
                (config.destName ?: stringResource(Res.string.range_test_target_all)),
        )
        Text(
            "${stringResource(Res.string.range_test_summary_interval)}: " +
                stringResource(Res.string.range_test_seconds, config.intervalSec),
        )
        val endsAt = state.endsAt
        if (state.isSending && endsAt != null) {
            Text(stringResource(Res.string.range_test_time_left, formatCountdown(endsAt - now)))
        }
        state.lastSentAt?.let {
            Text(
                stringResource(Res.string.range_test_last_sent, ((now - it) / MILLIS_PER_SECOND).toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RemoveChannelCard(channel: RangeTestChannelOption, onRemove: () -> Unit) {
    InfoCard(icon = MeshtasticIcons.Channel, title = stringResource(Res.string.range_test_remove_channel)) {
        Text(stringResource(Res.string.range_test_remove_channel_desc, channel.name))
        Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.range_test_remove_channel))
        }
    }
}

/** The mobile side while it records: counters, the last beacon, position source, map and stop. */
@Composable
fun RangeTestReceiverScreen(
    viewModel: RangeTestViewModel,
    onNavigateUp: () -> Unit,
    onOpenMap: (Long) -> Unit,
    onStopRecording: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    KeepScreenOn(enabled = state.isRecording)

    LiveScaffold(stringResource(Res.string.range_test_receiver_title), onNavigateUp, modifier) {
        if (!state.isRecording) {
            Text(stringResource(Res.string.range_test_recording_stopped), style = MaterialTheme.typography.bodyLarge)
            return@LiveScaffold
        }
        InfoCard(
            icon = MeshtasticIcons.NearMe,
            title = stringResource(Res.string.range_test_recording_to, state.targetName.orEmpty()),
            highlighted = true,
        ) {
            StatRow(
                listOf(
                    stringResource(Res.string.range_test_received) to state.received.toString(),
                    stringResource(Res.string.range_test_missed) to state.missed.toString(),
                    stringResource(Res.string.range_test_relayed) to state.relayed.toString(),
                    stringResource(Res.string.range_test_unplaced) to state.unplaced.toString(),
                ),
            )
            ReceiverSignal(state, units)
            LocationStatus(state.fix)
        }
        Button(
            onClick = { state.sessionId?.let(onOpenMap) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.range_test_open_map))
        }
        OutlinedButton(
            onClick = {
                val sessionId = state.sessionId
                viewModel.stopRecording()
                sessionId?.let(onStopRecording)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.range_test_stop))
        }
    }
}

/** Success rate, the last beacon (ticking age) and the farthest direct one. */
@Composable
private fun ReceiverSignal(state: RangeTestRecorderState, units: MeasurementSystem) {
    val now by rememberTickingNow()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val total = state.received + state.missed
        if (total > 0) {
            Text(
                "${stringResource(Res.string.range_test_success_rate)}: " +
                    "${(state.received * PERCENT / total).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        val heardAt = state.lastHeardAt
        if (heardAt == null) {
            Text(stringResource(Res.string.range_test_waiting_beacon))
        } else {
            val secondsAgo = ((now - heardAt) / MILLIS_PER_SECOND).toInt()
            Text(
                "${stringResource(Res.string.range_test_last_beacon)}: " +
                    signalText(state.lastSnr, state.lastRssi, state.lastHops) +
                    state.lastDistanceM?.let { " · ${formatDistance(it, units)}" }.orEmpty() +
                    " · " +
                    stringResource(Res.string.range_test_seconds_ago, secondsAgo),
            )
        }
        state.maxDirectDistanceM?.let {
            Text("${stringResource(Res.string.range_test_max_distance)}: ${formatDistance(it, units)}")
        }
    }
}

@Composable
private fun LiveScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(modifier = modifier, topBar = { BackTopBar(title, onNavigateUp) }) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = LIVE_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / MILLIS_PER_SECOND).toInt()
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

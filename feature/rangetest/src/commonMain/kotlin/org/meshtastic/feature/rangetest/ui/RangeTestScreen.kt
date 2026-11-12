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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.database.entity.isSender
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_delete_session
import org.meshtastic.core.resources.range_test_history
import org.meshtastic.core.resources.range_test_history_received_title
import org.meshtastic.core.resources.range_test_history_sent_counts
import org.meshtastic.core.resources.range_test_history_sent_title
import org.meshtastic.core.resources.range_test_hub_intro
import org.meshtastic.core.resources.range_test_in_progress
import org.meshtastic.core.resources.range_test_locked_title
import org.meshtastic.core.resources.range_test_new_test
import org.meshtastic.core.resources.range_test_no_sessions
import org.meshtastic.core.resources.range_test_open
import org.meshtastic.core.resources.range_test_open_map
import org.meshtastic.core.resources.range_test_received
import org.meshtastic.core.resources.range_test_recording_to
import org.meshtastic.core.resources.range_test_sending_done
import org.meshtastic.core.resources.range_test_sending_status
import org.meshtastic.core.resources.range_test_sent
import org.meshtastic.core.resources.range_test_target_all
import org.meshtastic.core.ui.icon.CellTower
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Distance
import org.meshtastic.core.ui.icon.Map
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.NearMe
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.feature.rangetest.RangeTestRecorderState
import org.meshtastic.feature.rangetest.RangeTestSenderState
import org.meshtastic.feature.rangetest.RangeTestViewModel

private val CONTENT_MAX_WIDTH = 640.dp

/**
 * The Range Test hub: what is running now, a button into the setup wizard, and the history of recorded tests. Content
 * is width-capped and centred so it stays readable on tablets and desktop.
 */
@Composable
fun RangeTestScreen(
    viewModel: RangeTestViewModel,
    onNavigateUp: () -> Unit,
    onStartWizard: () -> Unit,
    onOpenSender: () -> Unit,
    onOpenReceiver: () -> Unit,
    onOpenMap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recorder by viewModel.recorderState.collectAsStateWithLifecycle()
    val sender by viewModel.senderState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(stringResource(Res.string.range_test_locked_title), onNavigateUp) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                InfoCard(
                    icon = MeshtasticIcons.Distance,
                    title = stringResource(Res.string.range_test_locked_title),
                    modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
                ) {
                    Text(stringResource(Res.string.range_test_hub_intro), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (sender.config != null) {
                item { SenderStatusCard(sender, onOpenSender, Modifier.widthIn(max = CONTENT_MAX_WIDTH)) }
            }
            if (recorder.isRecording) {
                item { ReceiverStatusCard(recorder, onOpenReceiver, Modifier.widthIn(max = CONTENT_MAX_WIDTH)) }
            }
            item {
                Button(
                    onClick = onStartWizard,
                    modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(MeshtasticIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.range_test_new_test))
                }
            }
            item {
                Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.range_test_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (sessions.isEmpty()) {
                        Text(
                            stringResource(Res.string.range_test_no_sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    recording = session.id == recorder.sessionId,
                    onOpen = { onOpenMap(session.id) },
                    onDelete = { viewModel.deleteSession(session.id) },
                    modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
                )
            }
        }
    }
}

@Composable
private fun SenderStatusCard(state: RangeTestSenderState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val config = state.config ?: return
    InfoCard(
        icon = MeshtasticIcons.CellTower,
        title =
        if (state.isSending) {
            stringResource(Res.string.range_test_sending_status, config.channelName)
        } else {
            stringResource(Res.string.range_test_sending_done)
        },
        highlighted = state.isSending,
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Text("${stringResource(Res.string.range_test_sent)}: ${state.sent}")
        OpenRow(onOpen)
    }
}

@Composable
private fun ReceiverStatusCard(state: RangeTestRecorderState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    InfoCard(
        icon = MeshtasticIcons.NearMe,
        title = stringResource(Res.string.range_test_recording_to, state.targetName.orEmpty()),
        highlighted = true,
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Text("${stringResource(Res.string.range_test_received)}: ${state.received}")
        OpenRow(onOpen)
    }
}

@Composable
private fun OpenRow(onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onOpen) { Text(stringResource(Res.string.range_test_open)) }
    }
}

@Composable
private fun SessionRow(
    session: RangeTestSessionEntity,
    recording: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A sender run has no map: it only knows what it sent, so the row shows its counts instead.
    val clickable = if (session.isSender) Modifier else Modifier.clickable(onClick = onOpen)
    Row(
        modifier = modifier.fillMaxWidth().then(clickable).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sessionTitle(session), style = MaterialTheme.typography.titleSmall)
            val ended = session.endedAt
            Text(
                DateFormatter.formatDateTimeShort(session.startedAt) +
                    if (ended == null) " · " + stringResource(Res.string.range_test_in_progress) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.isSender) {
                Text(
                    stringResource(
                        Res.string.range_test_history_sent_counts,
                        session.beaconsSent ?: 0,
                        session.beaconsConfirmed ?: 0,
                        session.beaconsFailed ?: 0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!session.isSender) {
            IconButton(onClick = onOpen) {
                Icon(MeshtasticIcons.Map, contentDescription = stringResource(Res.string.range_test_open_map))
            }
        }
        if (!recording && session.endedAt != null) {
            IconButton(onClick = onDelete) {
                Icon(MeshtasticIcons.Delete, contentDescription = stringResource(Res.string.range_test_delete_session))
            }
        }
    }
}

@Composable
private fun sessionTitle(session: RangeTestSessionEntity): String = if (session.isSender) {
    val target = session.targetName.ifBlank { stringResource(Res.string.range_test_target_all) }
    stringResource(Res.string.range_test_history_sent_title, session.channelName.orEmpty()) + " · " + target
} else {
    stringResource(Res.string.range_test_history_received_title, session.targetName)
}

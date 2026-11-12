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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.entity.RangeTestLocationSource
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.range_test_delete_session
import org.meshtastic.core.resources.range_test_grant
import org.meshtastic.core.resources.range_test_in_progress
import org.meshtastic.core.resources.range_test_last_beacon
import org.meshtastic.core.resources.range_test_location_permission
import org.meshtastic.core.resources.range_test_location_phone
import org.meshtastic.core.resources.range_test_location_radio
import org.meshtastic.core.resources.range_test_locked_title
import org.meshtastic.core.resources.range_test_max_distance
import org.meshtastic.core.resources.range_test_missed
import org.meshtastic.core.resources.range_test_module_settings
import org.meshtastic.core.resources.range_test_no_candidates
import org.meshtastic.core.resources.range_test_no_sessions
import org.meshtastic.core.resources.range_test_open_map
import org.meshtastic.core.resources.range_test_pick_sender
import org.meshtastic.core.resources.range_test_received
import org.meshtastic.core.resources.range_test_recording_to
import org.meshtastic.core.resources.range_test_relayed
import org.meshtastic.core.resources.range_test_seconds_ago
import org.meshtastic.core.resources.range_test_sender_hint
import org.meshtastic.core.resources.range_test_sender_no_position
import org.meshtastic.core.resources.range_test_sessions
import org.meshtastic.core.resources.range_test_start
import org.meshtastic.core.resources.range_test_stop
import org.meshtastic.core.resources.range_test_success_rate
import org.meshtastic.core.resources.range_test_unplaced
import org.meshtastic.core.resources.range_test_waiting_beacon
import org.meshtastic.core.resources.range_test_waiting_fix
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Map
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberLocationPermissionState
import org.meshtastic.feature.rangetest.RangeTestCandidate
import org.meshtastic.feature.rangetest.RangeTestRecorderState
import org.meshtastic.feature.rangetest.RangeTestViewModel

private const val TICK_MS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val PERCENT = 100f

@Composable
fun RangeTestScreen(
    viewModel: RangeTestViewModel,
    onNavigateUp: () -> Unit,
    onOpenMap: (Long) -> Unit,
    onOpenModuleSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.recorderState.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    var selectedNum by rememberSaveable { mutableStateOf<Int?>(null) }

    KeepScreenOn(enabled = state.isRecording)

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(stringResource(Res.string.range_test_locked_title), onNavigateUp) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SenderHintCard(onOpenModuleSettings) }
            if (state.isRecording) {
                item {
                    LiveCard(state, units, onOpenMap = { state.sessionId?.let(onOpenMap) }, onStop = viewModel::stop)
                }
            } else {
                senderPicker(
                    candidates = candidates,
                    selectedNum = selectedNum,
                    onSelect = { selectedNum = it },
                    onStart = viewModel::start,
                )
            }
            sessionList(
                sessions = sessions,
                recordingId = state.sessionId,
                onOpen = onOpenMap,
                onDelete = viewModel::deleteSession,
            )
        }
    }
}

/** Title plus a back arrow, shared by the range-test screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopBar(title: String, onNavigateUp: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        },
        actions = actions,
    )
}

@Composable
private fun SenderHintCard(onOpenModuleSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.range_test_sender_hint), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenModuleSettings) {
                Text(stringResource(Res.string.range_test_module_settings))
            }
        }
    }
}

private fun LazyListScope.senderPicker(
    candidates: List<RangeTestCandidate>,
    selectedNum: Int?,
    onSelect: (Int) -> Unit,
    onStart: (Node) -> Unit,
) {
    item { Text(stringResource(Res.string.range_test_pick_sender), style = MaterialTheme.typography.titleMedium) }
    if (candidates.isEmpty()) {
        item { Text(stringResource(Res.string.range_test_no_candidates)) }
    }
    items(candidates, key = { it.node.num }) { candidate ->
        CandidateRow(candidate, selected = candidate.node.num == selectedNum) { onSelect(candidate.node.num) }
    }
    item {
        val selected = candidates.firstOrNull { it.node.num == selectedNum }
        Button(
            onClick = { selected?.let { onStart(it.node) } },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.range_test_start))
        }
    }
}

private fun LazyListScope.sessionList(
    sessions: List<RangeTestSessionEntity>,
    recordingId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    item {
        HorizontalDivider()
        Text(
            stringResource(Res.string.range_test_sessions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (sessions.isEmpty()) {
        item { Text(stringResource(Res.string.range_test_no_sessions)) }
    }
    items(sessions, key = { it.id }) { session ->
        SessionRow(
            session = session,
            recording = session.id == recordingId,
            onOpen = { onOpen(session.id) },
            onDelete = { onDelete(session.id) },
        )
    }
}

@Composable
private fun CandidateRow(candidate: RangeTestCandidate, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            val user = candidate.node.user
            Text("${user.long_name.ifBlank { user.id }} (${user.short_name})")
            if (!candidate.hasPosition) {
                Text(
                    stringResource(Res.string.range_test_sender_no_position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LiveCard(
    state: RangeTestRecorderState,
    units: MeasurementSystem,
    onOpenMap: () -> Unit,
    onStop: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.range_test_recording_to, state.targetName.orEmpty()),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(stringResource(Res.string.range_test_received), state.received.toString())
                Stat(stringResource(Res.string.range_test_missed), state.missed.toString())
                Stat(stringResource(Res.string.range_test_relayed), state.relayed.toString())
                Stat(stringResource(Res.string.range_test_unplaced), state.unplaced.toString())
            }
            LiveSignal(state, units)
            LocationStatus(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenMap) { Text(stringResource(Res.string.range_test_open_map)) }
                Button(onClick = onStop) { Text(stringResource(Res.string.range_test_stop)) }
            }
        }
    }
}

/** Success rate, the last beacon (ticking age) and the farthest direct one. */
@Composable
private fun LiveSignal(state: RangeTestRecorderState, units: MeasurementSystem) {
    var now by remember { mutableLongStateOf(nowMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = nowMillis
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val total = state.received + state.missed
        if (total > 0) {
            Text(
                "${stringResource(Res.string.range_test_success_rate)}: " +
                    "${(state.received * PERCENT / total).toInt()}%",
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

/** Which GPS tags the beacons, and a way to grant the phone's location when it is missing. */
@Composable
private fun LocationStatus(state: RangeTestRecorderState) {
    val permission = rememberLocationPermissionState()
    Column {
        val fix = state.fix
        Text(
            when {
                fix == null -> stringResource(Res.string.range_test_waiting_fix)
                fix.source == RangeTestLocationSource.RADIO -> stringResource(Res.string.range_test_location_radio)
                else -> stringResource(Res.string.range_test_location_phone)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (permission.status != PermissionStatus.GRANTED) {
            Text(
                stringResource(Res.string.range_test_location_permission),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick =
                if (permission.status == PermissionStatus.PERMANENTLY_DENIED) {
                    permission.openAppSettings
                } else {
                    permission.request
                },
            ) {
                Text(stringResource(Res.string.range_test_grant))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionRow(session: RangeTestSessionEntity, recording: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.targetName, style = MaterialTheme.typography.titleSmall)
            val ended = session.endedAt
            Text(
                DateFormatter.formatDateTimeShort(session.startedAt) +
                    if (ended == null) " · " + stringResource(Res.string.range_test_in_progress) else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(MeshtasticIcons.Map, contentDescription = stringResource(Res.string.range_test_open_map))
        }
        if (!recording) {
            IconButton(onClick = onDelete) {
                Icon(MeshtasticIcons.Delete, contentDescription = stringResource(Res.string.range_test_delete_session))
            }
        }
    }
}

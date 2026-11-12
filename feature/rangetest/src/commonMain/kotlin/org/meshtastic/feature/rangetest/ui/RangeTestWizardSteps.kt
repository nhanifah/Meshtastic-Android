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
@file:Suppress("TooManyFunctions")

package org.meshtastic.feature.rangetest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_channel_create
import org.meshtastic.core.resources.range_test_channel_create_desc
import org.meshtastic.core.resources.range_test_channel_existing
import org.meshtastic.core.resources.range_test_channel_intro_receiver
import org.meshtastic.core.resources.range_test_channel_intro_sender
import org.meshtastic.core.resources.range_test_channel_joined
import org.meshtastic.core.resources.range_test_channel_label
import org.meshtastic.core.resources.range_test_clear_search
import org.meshtastic.core.resources.range_test_duration
import org.meshtastic.core.resources.range_test_heard_ago
import org.meshtastic.core.resources.range_test_interval
import org.meshtastic.core.resources.range_test_join
import org.meshtastic.core.resources.range_test_keep_connected
import org.meshtastic.core.resources.range_test_location_intro
import org.meshtastic.core.resources.range_test_location_phone_ok
import org.meshtastic.core.resources.range_test_minutes
import org.meshtastic.core.resources.range_test_mode
import org.meshtastic.core.resources.range_test_mode_direct
import org.meshtastic.core.resources.range_test_mode_direct_desc
import org.meshtastic.core.resources.range_test_mode_message
import org.meshtastic.core.resources.range_test_mode_message_desc
import org.meshtastic.core.resources.range_test_no_extra_channels
import org.meshtastic.core.resources.range_test_no_node_match
import org.meshtastic.core.resources.range_test_paste_code
import org.meshtastic.core.resources.range_test_pick_from_nodes
import org.meshtastic.core.resources.range_test_pick_sender_intro
import org.meshtastic.core.resources.range_test_radio_gps_none
import org.meshtastic.core.resources.range_test_radio_gps_ok
import org.meshtastic.core.resources.range_test_role_receiver
import org.meshtastic.core.resources.range_test_role_receiver_desc
import org.meshtastic.core.resources.range_test_role_sender
import org.meshtastic.core.resources.range_test_role_sender_desc
import org.meshtastic.core.resources.range_test_scan_qr
import org.meshtastic.core.resources.range_test_search_nodes
import org.meshtastic.core.resources.range_test_seconds
import org.meshtastic.core.resources.range_test_sender_no_position
import org.meshtastic.core.resources.range_test_share_intro
import org.meshtastic.core.resources.range_test_share_preset
import org.meshtastic.core.resources.range_test_summary_channel
import org.meshtastic.core.resources.range_test_summary_duration
import org.meshtastic.core.resources.range_test_summary_interval
import org.meshtastic.core.resources.range_test_summary_role
import org.meshtastic.core.resources.range_test_summary_sender
import org.meshtastic.core.resources.range_test_summary_target
import org.meshtastic.core.resources.range_test_target_all
import org.meshtastic.core.resources.range_test_target_all_desc
import org.meshtastic.core.resources.range_test_target_one
import org.meshtastic.core.resources.range_test_target_one_desc
import org.meshtastic.core.resources.range_test_until_stopped
import org.meshtastic.core.resources.range_test_waiting_senders
import org.meshtastic.core.ui.icon.CellTower
import org.meshtastic.core.ui.icon.Channel
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.NearMe
import org.meshtastic.core.ui.icon.QrCodeScanner
import org.meshtastic.core.ui.icon.Search
import org.meshtastic.core.ui.util.LocalBarcodeScannerProvider
import org.meshtastic.core.ui.util.LocalBarcodeScannerSupported
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberLocationPermissionState
import org.meshtastic.core.ui.util.rememberQrCodePainter
import org.meshtastic.feature.rangetest.RangeTestBeaconMode
import org.meshtastic.feature.rangetest.RangeTestChannelOption
import org.meshtastic.feature.rangetest.RangeTestDetectedSender
import org.meshtastic.feature.rangetest.RangeTestNodeOption
import org.meshtastic.feature.rangetest.RangeTestRole
import org.meshtastic.feature.rangetest.RangeTestSenderConfig
import org.meshtastic.feature.rangetest.RangeTestWizardState
import org.meshtastic.feature.rangetest.RangeTestWizardStep

private const val MILLIS_PER_SECOND = 1_000L
private val QR_SIZE = 240.dp

/** What the steps show, collected once by the screen. */
internal data class WizardData(
    val channels: List<RangeTestChannelOption> = emptyList(),
    val nodes: List<RangeTestNodeOption> = emptyList(),
    val detected: List<RangeTestDetectedSender> = emptyList(),
    val radioHasGps: Boolean = false,
    /** Sender's QR link for the chosen channel. */
    val shareCode: String? = null,
)

/** Every choice a step can make, as plain callbacks. */
internal data class WizardActions(
    val selectRole: (RangeTestRole) -> Unit,
    val setCreateChannel: (Boolean) -> Unit,
    val selectChannel: (Int) -> Unit,
    val joinFromCode: (String) -> Unit,
    val setBroadcast: (Boolean) -> Unit,
    val selectDestination: (Int) -> Unit,
    val setInterval: (Int) -> Unit,
    val setDuration: (Int?) -> Unit,
    val setMode: (RangeTestBeaconMode) -> Unit,
    val selectSender: (Int) -> Unit,
    val setNodeQuery: (String) -> Unit,
)

internal fun LazyListScope.wizardStep(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    itemModifier: Modifier,
) {
    when (state.step) {
        RangeTestWizardStep.ROLE -> roleStep(state, actions, itemModifier)

        RangeTestWizardStep.CHANNEL ->
            if (state.role == RangeTestRole.SENDER) {
                senderChannelStep(state, data, actions, itemModifier)
            } else {
                receiverChannelStep(state, data, actions, itemModifier)
            }

        RangeTestWizardStep.SHARE -> item("share") { ShareStep(state, data, itemModifier) }

        RangeTestWizardStep.TARGET -> targetStep(state, data, actions, itemModifier)

        RangeTestWizardStep.BEACON -> item("beacon") { BeaconStep(state, actions, itemModifier) }

        RangeTestWizardStep.PICK_SENDER -> pickSenderStep(state, data, actions, itemModifier)

        RangeTestWizardStep.LOCATION -> item("location") { LocationStep(data, itemModifier) }

        RangeTestWizardStep.SUMMARY -> item("summary") { SummaryStep(state, data, itemModifier) }
    }
}

// ---- Role -------------------------------------------------------------------------------------------------------

private fun LazyListScope.roleStep(state: RangeTestWizardState, actions: WizardActions, modifier: Modifier) {
    item("role-sender") {
        ChoiceCard(
            icon = MeshtasticIcons.CellTower,
            title = stringResource(Res.string.range_test_role_sender),
            description = stringResource(Res.string.range_test_role_sender_desc),
            selected = state.role == RangeTestRole.SENDER,
            onClick = { actions.selectRole(RangeTestRole.SENDER) },
            modifier = modifier,
        )
    }
    item("role-receiver") {
        ChoiceCard(
            icon = MeshtasticIcons.NearMe,
            title = stringResource(Res.string.range_test_role_receiver),
            description = stringResource(Res.string.range_test_role_receiver_desc),
            selected = state.role == RangeTestRole.RECEIVER,
            onClick = { actions.selectRole(RangeTestRole.RECEIVER) },
            modifier = modifier,
        )
    }
}

// ---- Channel ----------------------------------------------------------------------------------------------------

private fun LazyListScope.senderChannelStep(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier = Modifier,
) {
    item("channel-intro") { Hint(stringResource(Res.string.range_test_channel_intro_sender), modifier) }
    item("channel-create") {
        ChoiceCard(
            icon = MeshtasticIcons.Channel,
            title = stringResource(Res.string.range_test_channel_create),
            description = stringResource(Res.string.range_test_channel_create_desc),
            selected = state.createChannel,
            onClick = { actions.setCreateChannel(true) },
            modifier = modifier,
        )
    }
    existingChannels(state, data, actions, modifier, selected = { !state.createChannel && state.channelIndex == it })
}

private fun LazyListScope.receiverChannelStep(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier = Modifier,
) {
    item("channel-intro") { Hint(stringResource(Res.string.range_test_channel_intro_receiver), modifier) }
    item("channel-join") { JoinChannelCard(state, data, actions, modifier) }
    existingChannels(state, data, actions, modifier, selected = { state.channelIndex == it })
}

private fun LazyListScope.existingChannels(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier,
    selected: (Int) -> Boolean,
) {
    val extra = data.channels.filter { !it.isPrimary }
    item("channel-existing-title") {
        SectionLabel(stringResource(Res.string.range_test_channel_existing), modifier)
    }
    if (extra.isEmpty()) {
        item("channel-none") { Hint(stringResource(Res.string.range_test_no_extra_channels), modifier) }
    }
    items(extra, key = { "channel-${it.index}" }) { channel ->
        RadioRow(
            title = channel.name,
            selected = selected(channel.index),
            onClick = { actions.selectChannel(channel.index) },
            enabled = !state.busy,
            modifier = modifier,
        )
    }
}

@Composable
private fun JoinChannelCard(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier = Modifier,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val scanner = LocalBarcodeScannerProvider.current { result -> result?.let(actions.joinFromCode) }
    val scanSupported = LocalBarcodeScannerSupported.current
    InfoCard(
        icon = MeshtasticIcons.QrCodeScanner,
        title = stringResource(Res.string.range_test_scan_qr),
        modifier = modifier,
    ) {
        if (scanSupported) {
            Button(onClick = scanner::startScan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.range_test_scan_qr))
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(Res.string.range_test_paste_code)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { actions.joinFromCode(code) },
            enabled = code.isNotBlank() && !state.busy,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(Res.string.range_test_join))
        }
        val joined = data.channels.firstOrNull { it.index == state.channelIndex }
        if (joined != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MeshtasticIcons.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(Res.string.range_test_channel_joined, joined.name),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---- Share ------------------------------------------------------------------------------------------------------

@Composable
private fun ShareStep(state: RangeTestWizardState, data: WizardData, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint(stringResource(Res.string.range_test_share_intro), Modifier.fillMaxWidth())
        val name = data.channels.firstOrNull { it.index == state.channelIndex }?.name.orEmpty()
        Text(stringResource(Res.string.range_test_channel_label, name), style = MaterialTheme.typography.titleMedium)
        data.shareCode?.let { link ->
            // QR codes need a light quiet zone to scan, so the code sits on white in dark mode too.
            Box(
                Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(rememberQrCodePainter(link), contentDescription = null, modifier = Modifier.size(QR_SIZE))
            }
        }
        Hint(stringResource(Res.string.range_test_share_preset), Modifier.fillMaxWidth())
    }
}

// ---- Target -----------------------------------------------------------------------------------------------------

private fun LazyListScope.targetStep(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier = Modifier,
) {
    item("target-all") {
        ChoiceCard(
            icon = MeshtasticIcons.CellTower,
            title = stringResource(Res.string.range_test_target_all),
            description = stringResource(Res.string.range_test_target_all_desc),
            selected = state.broadcast,
            onClick = { actions.setBroadcast(true) },
            modifier = modifier,
        )
    }
    item("target-one") {
        ChoiceCard(
            icon = MeshtasticIcons.NearMe,
            title = stringResource(Res.string.range_test_target_one),
            description = stringResource(Res.string.range_test_target_one_desc),
            selected = !state.broadcast,
            onClick = { actions.setBroadcast(false) },
            modifier = modifier,
        )
    }
    if (!state.broadcast) {
        item("dest-search") { NodeSearchField(state.nodeQuery, actions.setNodeQuery, modifier) }
        val matches = data.nodes.filter { it.matches(state.nodeQuery) }
        if (matches.isEmpty()) item("dest-none") { Hint(stringResource(Res.string.range_test_no_node_match), modifier) }
        items(matches, key = { "dest-${it.num}" }) { node ->
            RadioRow(
                title = node.name,
                subtitle = node.shortName,
                selected = state.destNum == node.num,
                onClick = { actions.selectDestination(node.num) },
                modifier = modifier,
            )
        }
    }
}

// ---- Beacon -----------------------------------------------------------------------------------------------------

@Composable
private fun BeaconStep(state: RangeTestWizardState, actions: WizardActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(Res.string.range_test_interval))
        ChipRow {
            RangeTestSenderConfig.INTERVALS_SEC.forEach { seconds ->
                FilterChip(
                    selected = state.intervalSec == seconds,
                    onClick = { actions.setInterval(seconds) },
                    label = { Text(stringResource(Res.string.range_test_seconds, seconds)) },
                )
            }
        }
        SectionLabel(stringResource(Res.string.range_test_duration))
        ChipRow {
            RangeTestSenderConfig.DURATIONS_MIN.forEach { minutes ->
                FilterChip(
                    selected = state.durationMin == minutes,
                    onClick = { actions.setDuration(minutes) },
                    label = { Text(stringResource(Res.string.range_test_minutes, minutes)) },
                )
            }
            FilterChip(
                selected = state.durationMin == null,
                onClick = { actions.setDuration(null) },
                label = { Text(stringResource(Res.string.range_test_until_stopped)) },
            )
        }
        HorizontalDivider()
        SectionLabel(stringResource(Res.string.range_test_mode))
        RadioRow(
            title = stringResource(Res.string.range_test_mode_message),
            subtitle = stringResource(Res.string.range_test_mode_message_desc),
            selected = state.mode == RangeTestBeaconMode.MESSAGE,
            onClick = { actions.setMode(RangeTestBeaconMode.MESSAGE) },
        )
        RadioRow(
            title = stringResource(Res.string.range_test_mode_direct),
            subtitle = stringResource(Res.string.range_test_mode_direct_desc),
            selected = state.mode == RangeTestBeaconMode.DIRECT,
            onClick = { actions.setMode(RangeTestBeaconMode.DIRECT) },
        )
    }
}

// ---- Pick sender ------------------------------------------------------------------------------------------------

private fun LazyListScope.pickSenderStep(
    state: RangeTestWizardState,
    data: WizardData,
    actions: WizardActions,
    modifier: Modifier = Modifier,
) {
    item("pick-intro") { Hint(stringResource(Res.string.range_test_pick_sender_intro), modifier) }
    if (data.detected.isEmpty()) {
        item("pick-waiting") {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.range_test_waiting_senders), Modifier.padding(start = 12.dp))
            }
        }
    }
    items(data.detected, key = { "detected-${it.num}" }) { sender ->
        val now by rememberTickingNow()
        val secondsAgo = ((now - sender.lastHeardAt) / MILLIS_PER_SECOND).toInt()
        RadioRow(
            title = sender.name,
            subtitle =
            stringResource(Res.string.range_test_heard_ago, secondsAgo) +
                " · " +
                signalText(sender.snr, sender.rssi, hops = null),
            warning = if (sender.hasPosition) null else stringResource(Res.string.range_test_sender_no_position),
            selected = state.senderNum == sender.num,
            onClick = { actions.selectSender(sender.num) },
            modifier = modifier,
        )
    }
    item("pick-all-title") { SectionLabel(stringResource(Res.string.range_test_pick_from_nodes), modifier) }
    item("pick-search") { NodeSearchField(state.nodeQuery, actions.setNodeQuery, modifier) }
    val others = data.nodes.filter { n -> data.detected.none { it.num == n.num } && n.matches(state.nodeQuery) }
    if (others.isEmpty()) item("pick-none") { Hint(stringResource(Res.string.range_test_no_node_match), modifier) }
    items(others, key = { "node-${it.num}" }) { node ->
        RadioRow(
            title = node.name,
            subtitle = node.shortName,
            selected = state.senderNum == node.num,
            onClick = { actions.selectSender(node.num) },
            modifier = modifier,
        )
    }
}

// ---- Location ---------------------------------------------------------------------------------------------------

@Composable
private fun LocationStep(data: WizardData, modifier: Modifier = Modifier) {
    val permission = rememberLocationPermissionState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(stringResource(Res.string.range_test_location_intro))
        StatusLine(
            ok = data.radioHasGps,
            text =
            stringResource(
                if (data.radioHasGps) Res.string.range_test_radio_gps_ok else Res.string.range_test_radio_gps_none,
            ),
        )
        if (permission.status == PermissionStatus.GRANTED) {
            StatusLine(ok = true, text = stringResource(Res.string.range_test_location_phone_ok))
        } else {
            LocationPermissionPrompt(permission.status, permission.request, permission.openAppSettings)
        }
    }
}

// ---- Summary ----------------------------------------------------------------------------------------------------

@Composable
private fun SummaryStep(state: RangeTestWizardState, data: WizardData, modifier: Modifier = Modifier) {
    val channel = data.channels.firstOrNull { it.index == state.channelIndex }?.name.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val role =
                    if (state.role == RangeTestRole.SENDER) {
                        Res.string.range_test_role_sender
                    } else {
                        Res.string.range_test_role_receiver
                    }
                SummaryRow(stringResource(Res.string.range_test_summary_role), stringResource(role))
                SummaryRow(stringResource(Res.string.range_test_summary_channel), channel)
                if (state.role == RangeTestRole.SENDER) {
                    SenderSummary(state, data)
                } else {
                    val sender =
                        data.detected.firstOrNull { it.num == state.senderNum }?.name
                            ?: data.nodes.firstOrNull { it.num == state.senderNum }?.name
                    SummaryRow(stringResource(Res.string.range_test_summary_sender), sender.orEmpty())
                }
            }
        }
        if (state.role == RangeTestRole.SENDER) Hint(stringResource(Res.string.range_test_keep_connected))
    }
}

@Composable
private fun SenderSummary(state: RangeTestWizardState, data: WizardData) =
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val target =
            if (state.broadcast) {
                stringResource(Res.string.range_test_target_all)
            } else {
                data.nodes.firstOrNull { it.num == state.destNum }?.name.orEmpty()
            }
        SummaryRow(stringResource(Res.string.range_test_summary_target), target)
        SummaryRow(
            stringResource(Res.string.range_test_summary_interval),
            stringResource(Res.string.range_test_seconds, state.intervalSec),
        )
        SummaryRow(
            stringResource(Res.string.range_test_summary_duration),
            state.durationMin?.let { stringResource(Res.string.range_test_minutes, it) }
                ?: stringResource(Res.string.range_test_until_stopped),
        )
        SummaryRow(
            stringResource(Res.string.range_test_mode),
            stringResource(
                if (state.mode == RangeTestBeaconMode.MESSAGE) {
                    Res.string.range_test_mode_message
                } else {
                    Res.string.range_test_mode_direct
                },
            ),
        )
    }

// ---- Building blocks --------------------------------------------------------------------------------------------

/** Filters a node list by name, short name or node id; the clear button empties it. */
@Composable
private fun NodeSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.range_test_search_nodes)) },
        leadingIcon = { Icon(MeshtasticIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(MeshtasticIcons.Close, contentDescription = stringResource(Res.string.range_test_clear_search))
                }
            }
        },
    )
}

/** A large selectable card: icon, title and a one-line explanation. */
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    warning: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
        modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            MeshtasticIcons.CheckCircle,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Text(text, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
}

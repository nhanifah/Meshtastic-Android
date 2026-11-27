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
package org.meshtastic.feature.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.voice_autoplay
import org.meshtastic.core.resources.voice_codec
import org.meshtastic.core.resources.voice_codec_summary
import org.meshtastic.core.resources.voice_gateway
import org.meshtastic.core.resources.voice_gateway_summary
import org.meshtastic.core.resources.voice_lora_relay
import org.meshtastic.core.resources.voice_lora_relay_summary
import org.meshtastic.core.resources.voice_max_length
import org.meshtastic.core.resources.voice_mqtt_connected
import org.meshtastic.core.resources.voice_mqtt_connecting
import org.meshtastic.core.resources.voice_mqtt_no_network
import org.meshtastic.core.resources.voice_mqtt_off
import org.meshtastic.core.resources.voice_mqtt_server
import org.meshtastic.core.resources.voice_mqtt_server_summary
import org.meshtastic.core.resources.voice_not_supported
import org.meshtastic.core.resources.voice_open_mqtt_settings
import org.meshtastic.core.resources.voice_public_channels_blocked
import org.meshtastic.core.resources.voice_seconds
import org.meshtastic.core.resources.voice_settings
import org.meshtastic.core.resources.voice_transport
import org.meshtastic.core.resources.voice_transport_auto
import org.meshtastic.core.resources.voice_transport_both
import org.meshtastic.core.resources.voice_transport_lora
import org.meshtastic.core.resources.voice_transport_mqtt
import org.meshtastic.core.ui.component.DropDownPreference
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.core.ui.component.TitledCard
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.voice.VoiceCodec
import org.meshtastic.feature.voice.VoiceNoteTransport
import org.meshtastic.feature.voice.VoiceSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun VoiceSettingsScreen(
    viewModel: VoiceSettingsViewModel,
    onNavigateUp: () -> Unit,
    onOpenMqttSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mqtt by viewModel.mqttStatus.collectAsStateWithLifecycle()
    val codecId by viewModel.codecWireId.collectAsStateWithLifecycle()
    val maxSeconds by viewModel.maxNoteSeconds.collectAsStateWithLifecycle()
    val transport by viewModel.noteTransport.collectAsStateWithLifecycle()
    val relay by viewModel.loraRelay.collectAsStateWithLifecycle()
    val gateway by viewModel.gatewayEnabled.collectAsStateWithLifecycle()
    val autoplay by viewModel.autoplayLive.collectAsStateWithLifecycle()
    val enabled = viewModel.supported

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.voice_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!enabled) {
                item {
                    Text(
                        stringResource(Res.string.voice_not_supported),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                TitledCard(title = stringResource(Res.string.voice_mqtt_server)) {
                    val status =
                        when {
                            mqtt.disabledInRadioSettings -> stringResource(Res.string.voice_mqtt_off)

                            !mqtt.networkAvailable -> stringResource(Res.string.voice_mqtt_no_network)

                            mqtt.connected ->
                                stringResource(Res.string.voice_mqtt_connected, mqtt.serverAddress.orEmpty())

                            else -> stringResource(Res.string.voice_mqtt_connecting, mqtt.serverAddress.orEmpty())
                        }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        stringResource(Res.string.voice_mqtt_server_summary),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (mqtt.blockedPublicChannels.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.voice_public_channels_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    OutlinedButton(onClick = onOpenMqttSettings, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(Res.string.voice_open_mqtt_settings))
                    }
                }
            }
            item {
                TitledCard(title = stringResource(Res.string.voice_settings)) {
                    DropDownPreference(
                        title = stringResource(Res.string.voice_codec),
                        summary = stringResource(Res.string.voice_codec_summary),
                        enabled = enabled,
                        items = VoiceCodec.entries.map { it to "${it.bitsPerSecond} bps" },
                        selectedItem = VoiceCodec.fromWireId(codecId) ?: VoiceCodec.DEFAULT,
                        onItemSelected = viewModel::setCodec,
                    )
                    DropDownPreference(
                        title = stringResource(Res.string.voice_max_length),
                        enabled = enabled,
                        items =
                        VoiceSettingsViewModel.NOTE_LENGTHS_SECONDS.map {
                            it to stringResource(Res.string.voice_seconds, it)
                        },
                        selectedItem = maxSeconds,
                        onItemSelected = viewModel::setMaxNoteSeconds,
                    )
                    DropDownPreference(
                        title = stringResource(Res.string.voice_transport),
                        enabled = enabled,
                        items =
                        listOf(
                            VoiceNoteTransport.AUTO to stringResource(Res.string.voice_transport_auto),
                            VoiceNoteTransport.MQTT_ONLY to stringResource(Res.string.voice_transport_mqtt),
                            VoiceNoteTransport.LORA_ONLY to stringResource(Res.string.voice_transport_lora),
                            VoiceNoteTransport.BOTH to stringResource(Res.string.voice_transport_both),
                        ),
                        selectedItem =
                        runCatching { VoiceNoteTransport.valueOf(transport) }.getOrDefault(VoiceNoteTransport.AUTO),
                        onItemSelected = viewModel::setNoteTransport,
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.voice_lora_relay),
                        summary = stringResource(Res.string.voice_lora_relay_summary),
                        checked = relay,
                        enabled = enabled,
                        onCheckedChange = viewModel::setLoraRelay,
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.voice_gateway),
                        summary = stringResource(Res.string.voice_gateway_summary),
                        checked = gateway,
                        enabled = enabled,
                        onCheckedChange = viewModel::setGatewayEnabled,
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.voice_autoplay),
                        checked = autoplay,
                        enabled = enabled,
                        onCheckedChange = viewModel::setAutoplayLive,
                    )
                }
            }
        }
    }
}

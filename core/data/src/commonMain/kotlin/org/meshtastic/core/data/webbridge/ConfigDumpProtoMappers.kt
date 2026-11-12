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
package org.meshtastic.core.data.webbridge

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.proto.MyNodeInfo as ProtoMyNodeInfo
import org.meshtastic.proto.NodeInfo as ProtoNodeInfo

/*
 * Model → proto mappers that rebuild the radio's own config-dump messages from what the app has stored, so the phone
 * can answer a `want_config_id` itself instead of forwarding it (a second `want_config` on one session crashes the
 * firmware; see `MeshConnectionManagerImpl.scheduleStallGuard`).
 *
 * Only fields that came from the radio are written back. Local-only data (display name, notes, power channel labels)
 * never leaves the phone.
 */

/** Firmware always reports eight channel slots, filling unused ones with DISABLED entries. */
internal const val CHANNEL_SLOT_COUNT = 8

private const val MQTT_NAME_SUFFIX = " (MQTT)"

internal fun MyNodeInfo.toProto(): ProtoMyNodeInfo = ProtoMyNodeInfo.Builder()
    .also { wb ->
        wb.my_node_num = myNodeNum
        wb.min_app_version = minAppVersion
        // The model keeps device_id as hex (see MeshConfigFlowManagerImpl.buildMyNodeInfo); undo that here.
        wb.device_id = deviceId?.let { runCatching { it.decodeHex() }.getOrNull() } ?: ByteString.EMPTY
        wb.pio_env = pioEnv.orEmpty()
    }
    .build()

internal fun Node.toNodeInfoProto(): ProtoNodeInfo = ProtoNodeInfo.Builder()
    .also { wb ->
        wb.num = num
        wb.user = user.withoutMqttSuffix(viaMqtt)
        wb.position = position.takeUnless { it.hasNoFix() }
        if (snr != Float.MAX_VALUE) wb.snr = snr
        wb.last_heard = lastHeard
        wb.device_metrics = deviceMetrics.takeUnless { it == DeviceMetrics.Builder().build() }
        wb.channel = channel
        wb.via_mqtt = viaMqtt
        wb.hops_away = hopsAway.takeIf { it >= 0 }
        wb.is_favorite = isFavorite
        wb.is_ignored = isIgnored
        wb.is_muted = isMuted
        wb.is_key_manually_verified = manuallyVerified
        wb.has_xeddsa_signed = signsPackets
        wb.heard_on_current_lora = heardOnCurrentLora
    }
    .build()

/** The app appends " (MQTT)" to MQTT nodes' long names for display; the radio never sent it. */
private fun User.withoutMqttSuffix(viaMqtt: Boolean): User = if (viaMqtt && long_name.endsWith(MQTT_NAME_SUFFIX)) {
    newBuilder().also { wb -> wb.long_name = long_name.removeSuffix(MQTT_NAME_SUFFIX) }.build()
} else {
    this
}

private fun Position.hasNoFix(): Boolean = (latitude_i ?: 0) == 0 && (longitude_i ?: 0) == 0

/** Index 0 is PRIMARY, the rest of the stored settings are SECONDARY, and the remaining slots are DISABLED. */
internal fun ChannelSet.toChannelProtos(): List<Channel> = (0 until CHANNEL_SLOT_COUNT).map { index ->
    val settings = settings.getOrNull(index)
    Channel.Builder()
        .also { wb ->
            wb.index = index
            wb.settings = settings
            wb.role =
                when {
                    settings == null -> Channel.Role.DISABLED
                    index == 0 -> Channel.Role.PRIMARY
                    else -> Channel.Role.SECONDARY
                }
        }
        .build()
}

/** One [Config] per populated [LocalConfig] section, in the order firmware sends them. */
internal fun LocalConfig.toConfigProtos(): List<Config> = listOfNotNull(
    device?.let { v -> Config.Builder().also { it.device = v }.build() },
    position?.let { v -> Config.Builder().also { it.position = v }.build() },
    power?.let { v -> Config.Builder().also { it.power = v }.build() },
    network?.let { v -> Config.Builder().also { it.network = v }.build() },
    display?.let { v -> Config.Builder().also { it.display = v }.build() },
    lora?.let { v -> Config.Builder().also { it.lora = v }.build() },
    bluetooth?.let { v -> Config.Builder().also { it.bluetooth = v }.build() },
    security?.let { v -> Config.Builder().also { it.security = v }.build() },
)

/** One [ModuleConfig] per populated [LocalModuleConfig] section, in the order firmware sends them. */
internal fun LocalModuleConfig.toModuleConfigProtos(): List<ModuleConfig> = listOfNotNull(
    mqtt?.let { v -> ModuleConfig.Builder().also { it.mqtt = v }.build() },
    serial?.let { v -> ModuleConfig.Builder().also { it.serial = v }.build() },
    external_notification?.let { v -> ModuleConfig.Builder().also { it.external_notification = v }.build() },
    store_forward?.let { v -> ModuleConfig.Builder().also { it.store_forward = v }.build() },
    range_test?.let { v -> ModuleConfig.Builder().also { it.range_test = v }.build() },
    telemetry?.let { v -> ModuleConfig.Builder().also { it.telemetry = v }.build() },
    canned_message?.let { v -> ModuleConfig.Builder().also { it.canned_message = v }.build() },
    audio?.let { v -> ModuleConfig.Builder().also { it.audio = v }.build() },
    remote_hardware?.let { v -> ModuleConfig.Builder().also { it.remote_hardware = v }.build() },
    neighbor_info?.let { v -> ModuleConfig.Builder().also { it.neighbor_info = v }.build() },
    ambient_lighting?.let { v -> ModuleConfig.Builder().also { it.ambient_lighting = v }.build() },
    detection_sensor?.let { v -> ModuleConfig.Builder().also { it.detection_sensor = v }.build() },
    paxcounter?.let { v -> ModuleConfig.Builder().also { it.paxcounter = v }.build() },
    statusmessage?.let { v -> ModuleConfig.Builder().also { it.statusmessage = v }.build() },
    traffic_management?.let { v -> ModuleConfig.Builder().also { it.traffic_management = v }.build() },
    tak?.let { v -> ModuleConfig.Builder().also { it.tak = v }.build() },
    mesh_beacon?.let { v -> ModuleConfig.Builder().also { it.mesh_beacon = v }.build() },
)

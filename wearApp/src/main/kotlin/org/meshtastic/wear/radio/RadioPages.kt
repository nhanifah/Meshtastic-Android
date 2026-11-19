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
package org.meshtastic.wear.radio

import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalStats
import org.meshtastic.wear.ui.Here
import org.meshtastic.wear.ui.distanceTo
import org.meshtastic.wear.ui.formatDistance

/** One "label value" row, as the radio's OLED draws it. */
data class RadioLine(val label: String, val value: String)

/** One OLED frame: a title row, then either key/value rows or a block of [text]. */
data class RadioPage(val title: String, val lines: List<RadioLine> = emptyList(), val text: String? = null)

/** The newest text message the radio has seen. */
data class LastMessage(val from: String, val text: String, val timeMillis: Long, val channel: String?)

/** Everything the frames show, taken from what the radio reports; assembled by [RadioScreenViewModel]. */
data class RadioSnapshot(
    val ourNode: Node? = null,
    val stats: LocalStats = LocalStats.Builder().build(),
    /** Other nodes, most recently heard first. */
    val nodes: List<Node> = emptyList(),
    val onlineNodes: Int = 0,
    val totalNodes: Int = 0,
    val here: Here? = null,
    val imperial: Boolean = false,
    val lora: Config.LoRaConfig? = null,
    val channelName: String? = null,
    val frequencyMhz: Float? = null,
    val lastMessage: LastMessage? = null,
    val nowMillis: Long = 0L,
)

/**
 * The frames of the radio's built-in OLED, rebuilt from data: status, nodes, last message, position, LoRa, device and,
 * when the radio has a sensor, environment. Labels are the firmware's own short English labels, as on the device.
 */
fun radioPages(s: RadioSnapshot): List<RadioPage> = buildList {
    add(statusPage(s))
    add(nodesPage(s))
    add(messagePage(s))
    add(positionPage(s))
    add(loraPage(s))
    add(devicePage(s))
    s.ourNode?.takeIf { it.hasEnvironmentMetrics }?.let { add(environmentPage(it, s.imperial)) }
}

private fun statusPage(s: RadioSnapshot): RadioPage {
    val node = s.ourNode
    val metrics = node?.deviceMetrics
    val title = node?.let {
        listOf(it.user.short_name, it.effectiveLongName).filter(String::isNotBlank).joinToString(" ")
    }
    val uptime = s.stats.uptime_seconds.takeIf { it > 0 } ?: metrics?.uptime_seconds
    val channelUtil = s.stats.channel_utilization.takeIf { it > 0f } ?: metrics?.channel_utilization
    val airTx = s.stats.air_util_tx.takeIf { it > 0f } ?: metrics?.air_util_tx
    return RadioPage(
        title = title?.takeIf { it.isNotBlank() } ?: "Meshtastic",
        lines =
        listOfNotNull(
            RadioLine("Batt", battery(metrics?.battery_level, metrics?.voltage)),
            RadioLine("Nodes", "${s.onlineNodes}/${s.totalNodes} online"),
            channelUtil?.let { RadioLine("ChUtil", percent(it)) },
            airTx?.let { RadioLine("AirTx", percent(it)) },
            RadioLine("GPS", gps(node)),
            uptime?.let { RadioLine("Up", duration(it.toLong())) },
        ),
    )
}

private fun nodesPage(s: RadioSnapshot): RadioPage = RadioPage(
    title = "Nodes ${s.onlineNodes}/${s.totalNodes}",
    lines =
    s.nodes.take(MAX_NODE_ROWS).map { node ->
        val parts =
            listOfNotNull(
                ago(s.nowMillis, node.lastHeard * MILLIS_PER_SECOND),
                node.snrOrNull?.let { "${fixed(it.toDouble(), 1)}dB" },
                node.hopsAway.takeIf { it > 0 }?.let { "${it}h" },
                distanceTo(s.here, node)?.let { formatDistance(it, s.imperial) },
            )
        RadioLine(
            node.user.short_name.ifBlank { node.effectiveLongName.take(SHORT_NAME_FALLBACK) },
            parts.joinToString(" "),
        )
    },
    text = if (s.nodes.isEmpty()) "No nodes heard yet" else null,
)

private fun messagePage(s: RadioSnapshot): RadioPage {
    val message = s.lastMessage ?: return RadioPage(title = "Messages", text = "No messages")
    return RadioPage(
        title = listOfNotNull(message.from, ago(s.nowMillis, message.timeMillis)).joinToString(" "),
        lines = listOfNotNull(message.channel?.let { RadioLine("Ch", it) }),
        text = message.text,
    )
}

private fun positionPage(s: RadioSnapshot): RadioPage {
    val node = s.ourNode
    val position = node?.validPosition ?: return RadioPage(title = "Position", text = "No GPS fix")
    val altitude = position.altitude
    return RadioPage(
        title = "Position",
        lines =
        listOfNotNull(
            RadioLine("Lat", fixed(node.latitude, COORD_DIGITS)),
            RadioLine("Lon", fixed(node.longitude, COORD_DIGITS)),
            altitude?.let {
                if (s.imperial) {
                    RadioLine("Alt", "${(it * FEET_PER_METER).toInt()} ft")
                } else {
                    RadioLine("Alt", "$it m")
                }
            },
            position.sats_in_view.takeIf { it > 0 }?.let { RadioLine("Sats", "$it") },
            ago(s.nowMillis, position.time * MILLIS_PER_SECOND)?.let { RadioLine("Fix", it) },
        ),
    )
}

private fun loraPage(s: RadioSnapshot): RadioPage {
    val lora = s.lora
    val preset =
        when {
            lora == null -> null
            lora.use_preset -> lora.modem_preset.name.toPresetName()
            else -> "Custom"
        }
    return RadioPage(
        title = "LoRa",
        lines =
        listOfNotNull(
            lora?.let { RadioLine("Region", it.region.name) },
            preset?.let { RadioLine("Preset", it) },
            s.channelName?.takeIf { it.isNotBlank() }?.let { RadioLine("Ch", it) },
            s.frequencyMhz
                ?.takeIf { it > 0f }
                ?.let { RadioLine("Freq", "${fixed(it.toDouble(), FREQ_DIGITS)} MHz") },
            lora?.let { RadioLine("Hops", "${it.hop_limit}") },
            RadioLine("Pkts", "TX ${s.stats.num_packets_tx} RX ${s.stats.num_packets_rx}"),
            s.stats.num_packets_rx_bad.takeIf { it > 0 }?.let { RadioLine("Bad", "$it") },
            s.stats.noise_floor.takeIf { it != 0 }?.let { RadioLine("Noise", "$it dBm") },
        ),
    )
}

private fun devicePage(s: RadioSnapshot): RadioPage {
    val node = s.ourNode ?: return RadioPage(title = "Device", text = "Not connected")
    val metadata = node.metadata
    val heap =
        s.stats.heap_total_bytes
            .takeIf { it > 0 }
            ?.let { "${s.stats.heap_free_bytes / BYTES_PER_KB}/${it / BYTES_PER_KB} KB" }
    return RadioPage(
        title = node.effectiveLongName.ifBlank { "Device" },
        lines =
        listOfNotNull(
            node.user.id.takeIf { it.isNotBlank() }?.let { RadioLine("ID", it) },
            RadioLine("HW", node.user.hw_model.name),
            metadata?.firmware_version?.takeIf { it.isNotBlank() }?.let { RadioLine("FW", it) },
            RadioLine("Role", (metadata?.role ?: node.user.role).name.toPresetName()),
            heap?.let { RadioLine("Heap", it) },
        ),
    )
}

private fun environmentPage(node: Node, imperial: Boolean): RadioPage {
    val env = node.environmentMetrics
    return RadioPage(
        title = "Environment",
        lines =
        listOfNotNull(
            env.temperature?.let {
                if (imperial) {
                    RadioLine("Temp", "${fixed(it * CELSIUS_TO_F_SCALE + CELSIUS_TO_F_OFFSET, 1)}°F")
                } else {
                    RadioLine("Temp", "${fixed(it.toDouble(), 1)}°C")
                }
            },
            env.relative_humidity?.let { RadioLine("Hum", percent(it)) },
            env.barometric_pressure?.let { RadioLine("Press", "${fixed(it.toDouble(), 1)} hPa") },
            env.iaq?.let { RadioLine("IAQ", "$it") },
            env.lux?.let { RadioLine("Lux", fixed(it.toDouble(), 0)) },
        ),
    )
}

private fun gps(node: Node?): String {
    val position = node?.validPosition ?: return "No fix"
    return position.sats_in_view.takeIf { it > 0 }?.let { "$it sats" } ?: "Fix"
}

private const val MAX_NODE_ROWS = 6
private const val SHORT_NAME_FALLBACK = 4
private const val COORD_DIGITS = 5
private const val FREQ_DIGITS = 3
private const val BYTES_PER_KB = 1024
private const val MILLIS_PER_SECOND = 1000L
private const val FEET_PER_METER = 3.28084
private const val CELSIUS_TO_F_SCALE = 1.8
private const val CELSIUS_TO_F_OFFSET = 32.0

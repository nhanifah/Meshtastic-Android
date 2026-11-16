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
package org.meshtastic.wear.surface

import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.connected
import org.meshtastic.core.resources.connecting
import org.meshtastic.core.resources.disconnected
import org.meshtastic.core.resources.messages
import org.meshtastic.core.resources.wear_online_count
import org.meshtastic.core.resources.wear_unread_count

/** What the tile and complications show, read once per request. */
data class MeshSnapshot(
    val connected: Boolean,
    val statusText: String,
    val nodeName: String?,
    val onlineText: String,
    val unread: Int,
    val unreadText: String,
    val messagesLabel: String,
    val battery: Int?,
)

suspend fun readSnapshot(radio: RadioInterfaceService, nodes: NodeRepository, packets: PacketRepository): MeshSnapshot {
    val state = radio.connectionState.value
    val ourNode = nodes.ourNodeInfo.value
    val unread = packets.getUnreadCountTotal().first()
    val status =
        when (state) {
            ConnectionState.Connected -> Res.string.connected

            ConnectionState.Connecting -> Res.string.connecting

            ConnectionState.DeviceSleep,
            ConnectionState.Disconnected,
            -> Res.string.disconnected
        }
    return MeshSnapshot(
        connected = state == ConnectionState.Connected,
        statusText = getString(status),
        nodeName = ourNode?.user?.short_name?.takeIf { it.isNotBlank() },
        onlineText = getString(Res.string.wear_online_count, nodes.onlineNodeCount.first()),
        unread = unread,
        unreadText = getString(Res.string.wear_unread_count, unread),
        messagesLabel = getString(Res.string.messages),
        battery = ourNode?.batteryLevel?.takeIf { it in 0..MAX_BATTERY },
    )
}

const val MAX_BATTERY = 100

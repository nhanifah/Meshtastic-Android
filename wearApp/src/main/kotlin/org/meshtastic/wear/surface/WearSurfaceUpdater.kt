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

import android.app.Application
import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.wear.complication.MeshComplicationService
import org.meshtastic.wear.tile.MeshStatusTileService

/** Asks the system to redraw the tile and complications when what they show changes. */
@Single
class WearSurfaceUpdater(
    private val context: Application,
    private val radio: RadioInterfaceService,
    private val nodes: NodeRepository,
    private val packets: PacketRepository,
    private val scope: ApplicationCoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                radio.connectionState,
                nodes.onlineNodeCount,
                packets.getUnreadCountTotal(),
                nodes.ourNodeInfo,
            ) { state, online, unread, ourNode ->
                listOf(state, online, unread, ourNode?.batteryLevel)
            }
                .distinctUntilChanged()
                .debounce(UPDATE_DEBOUNCE_MS)
                .collect { requestUpdates() }
        }
    }

    private fun requestUpdates() {
        TileService.getUpdater(context).requestUpdate(MeshStatusTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(
            context,
            ComponentName(context, MeshComplicationService::class.java),
        )
            .requestUpdateAll()
    }

    private companion object {
        const val UPDATE_DEBOUNCE_MS = 2_000L
    }
}

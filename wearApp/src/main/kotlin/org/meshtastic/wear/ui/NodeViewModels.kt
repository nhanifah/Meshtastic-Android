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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.LocaleUnitsProvider
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.bearing
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PhoneLocation
import org.meshtastic.core.repository.PhoneLocationProvider
import org.meshtastic.core.repository.PhoneLocationState

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

/** Where the wearer is: the watch's own GPS, else the radio's position. */
data class Here(val latitude: Double, val longitude: Double)

private fun hereOf(location: PhoneLocation?, ourNode: Node?): Here? = when {
    location != null -> Here(location.latitude, location.longitude)
    ourNode?.validPosition != null -> Here(ourNode.latitude, ourNode.longitude)
    else -> null
}

/** Metres from [here] to [node], or null when either position is unknown. */
fun distanceTo(here: Here?, node: Node): Double? = if (here == null || node.validPosition == null) {
    null
} else {
    latLongToMeter(here.latitude, here.longitude, node.latitude, node.longitude)
}

/** Degrees clockwise from true north from [here] to [node], or null when either position is unknown. */
fun bearingTo(here: Here?, node: Node): Double? = if (here == null || node.validPosition == null) {
    null
} else {
    bearing(here.latitude, here.longitude, node.latitude, node.longitude)
}

data class NodesState(
    val nodes: List<Node> = emptyList(),
    val here: Here? = null,
    val units: MeasurementSystem = MeasurementSystem.METRIC,
    val myNum: Int? = null,
)

@KoinViewModel
class NodesViewModel(
    nodeRepository: NodeRepository,
    locationProvider: PhoneLocationProvider,
    unitsProvider: LocaleUnitsProvider,
) : ViewModel() {
    val state: StateFlow<NodesState> =
        combine(
            nodeRepository.nodeDBbyNum,
            nodeRepository.ourNodeInfo,
            locationProvider.locationUpdates(),
            unitsProvider.measurementSystem,
        ) { nodes, ourNode, location: PhoneLocationState, units ->
            NodesState(
                nodes =
                nodes.values
                    .filter { it.num != ourNode?.num && !it.isIgnored }
                    .sortedWith(compareByDescending<Node> { it.isFavorite }.thenByDescending { it.lastHeard }),
                here = hereOf(location.location, ourNode),
                units = units,
                myNum = ourNode?.num,
            )
        }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, NodesState())
}

data class NodeDetailState(
    val node: Node? = null,
    val here: Here? = null,
    val hasWatchFix: Boolean = false,
    val units: MeasurementSystem = MeasurementSystem.METRIC,
)

@KoinViewModel
class NodeDetailViewModel(
    @InjectedParam private val nodeNum: Int,
    nodeRepository: NodeRepository,
    locationProvider: PhoneLocationProvider,
    unitsProvider: LocaleUnitsProvider,
) : ViewModel() {
    val state: StateFlow<NodeDetailState> =
        combine(
            nodeRepository.nodeDBbyNum.map { it[nodeNum] },
            nodeRepository.ourNodeInfo,
            locationProvider.locationUpdates(),
            unitsProvider.measurementSystem,
        ) { node, ourNode, location, units ->
            NodeDetailState(
                node = node,
                here = hereOf(location.location, ourNode),
                hasWatchFix = location.hasFix,
                units = units,
            )
        }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, NodeDetailState())
}

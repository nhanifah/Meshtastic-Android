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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.LocaleUnitsProvider
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.isBroadcast
import org.meshtastic.core.model.isFromLocal
import org.meshtastic.core.model.util.getChannel
import org.meshtastic.core.model.util.primaryChannel
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PhoneLocationProvider
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.LocalConfig
import org.meshtastic.wear.ui.hereOf

/**
 * Feeds the watch's copy of the radio's OLED. Everything comes from what the radio already reports over the API (node
 * DB, local stats telemetry, config, messages), so it works on any firmware, over Bluetooth or through the phone.
 */
@KoinViewModel
class RadioScreenViewModel(
    nodeRepository: NodeRepository,
    packetRepository: PacketRepository,
    radioConfigRepository: RadioConfigRepository,
    locationProvider: PhoneLocationProvider,
    unitsProvider: LocaleUnitsProvider,
) : ViewModel() {

    private val radio: Flow<RadioSnapshot> =
        combine(
            nodeRepository.ourNodeInfo,
            nodeRepository.nodeDBbyNum,
            nodeRepository.localStats,
            nodeRepository.onlineNodeCount,
            nodeRepository.totalNodeCount,
        ) { ourNode, nodes, stats, online, total ->
            RadioSnapshot(
                ourNode = ourNode,
                stats = stats,
                nodes =
                nodes.values.filter { it.num != ourNode?.num && !it.isIgnored }.sortedByDescending { it.lastHeard },
                onlineNodes = online,
                totalNodes = total,
            )
        }

    /** Re-renders "heard 2m ago" style ages even when no new data arrives. */
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private val config: Flow<Pair<ChannelSet, LocalConfig>> =
        combine(radioConfigRepository.channelSetFlow, radioConfigRepository.localConfigFlow, ticker) { set, local, _ ->
            set to local
        }

    private val lastMessage: Flow<DataPacket?> =
        packetRepository.getContacts().map { contacts ->
            contacts.values.filter { !it.text.isNullOrBlank() }.maxByOrNull { it.time }
        }

    val pages: StateFlow<List<RadioPage>> =
        combine(
            radio,
            config,
            lastMessage,
            locationProvider.locationUpdates(),
            unitsProvider.measurementSystem,
        ) { snapshot, (channelSet, localConfig), packet, location, units ->
            val myNum = snapshot.ourNode?.num
            val primary = channelSet.primaryChannel
            val message = packet?.let {
                val sender = if (it.isFromLocal(myNum)) snapshot.ourNode else nodeRepository.getNode(it.from ?: "")
                LastMessage(
                    from = sender?.user?.short_name?.ifBlank { null } ?: sender?.user?.long_name ?: "?",
                    text = it.text.orEmpty(),
                    timeMillis = it.time,
                    channel = if (it.isBroadcast) channelSet.getChannel(it.channel)?.name else "DM",
                )
            }
            radioPages(
                snapshot.copy(
                    here = hereOf(location.location, snapshot.ourNode),
                    imperial = units == MeasurementSystem.IMPERIAL,
                    lora = localConfig.lora,
                    channelName = primary?.name,
                    frequencyMhz = primary?.radioFreq,
                    lastMessage = message,
                    nowMillis = nowMillis,
                ),
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), radioPages(RadioSnapshot()))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 10_000L
    }
}

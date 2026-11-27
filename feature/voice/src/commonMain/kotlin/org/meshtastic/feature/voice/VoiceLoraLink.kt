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
package org.meshtastic.feature.voice

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.Channel
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.repository.VoicePrefs
import org.meshtastic.proto.PortNum

/** A voice packet heard over LoRa, with the conversation it belongs to. */
data class LoraVoiceIn(val packet: VoicePacket, val fromNum: Int, val channelIndex: Int, val broadcast: Boolean)

/**
 * Voice over the connected radio: [VoicePacket]s go out as `PRIVATE_APP` data packets and come back from the mesh
 * packet stream. Pacing is the caller's job ([packetMs] says how long each one keeps the channel busy).
 */
@Single
class VoiceLoraLink(
    private val commandSender: CommandSender,
    private val serviceRepository: ServiceRepository,
    radioConfigRepository: RadioConfigRepository,
    private val nodeRepository: NodeRepository,
    private val prefs: VoicePrefs,
    scope: ApplicationCoroutineScope,
) {
    val radioReady: StateFlow<Boolean> =
        serviceRepository.connectionState
            .map { it is ConnectionState.Connected }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val modulation: StateFlow<LoraModulation> =
        radioConfigRepository.localConfigFlow
            .map { LoraModulation.of(it.lora ?: Channel.default.loraConfig) }
            .stateIn(scope, SharingStarted.Eagerly, LoraModulation.of(Channel.default.loraConfig))

    private val airUtilTx: Float
        get() = nodeRepository.myNodeInfo.value?.airUtilTx ?: 0f

    private val channelUtil: Float
        get() = nodeRepository.myNodeInfo.value?.channelUtilization ?: 0f

    fun noteBudget(clip: VoiceClip): LoraVoiceBudget =
        LoraVoicePolicy.forNote(modulation.value, clip, airUtilTx, channelUtil)

    fun liveBudget(codec: VoiceCodec): LoraVoiceBudget =
        LoraVoicePolicy.forLive(modulation.value, codec, airUtilTx, channelUtil)

    fun packetMs(packet: VoicePacket): Long =
        VoiceAirtime.packetMs(modulation.value, VoiceWire.HEADER_SIZE + packet.frames.size).toLong()

    /** Hands one packet to the radio; false when it is not connected or its queue refused the packet. */
    suspend fun send(target: VoiceTarget, packet: VoicePacket, wantAck: Boolean): Boolean {
        if (!radioReady.value) return false
        val data =
            DataPacket(
                to = target.destNum?.let(NodeAddress::numToDefaultId) ?: NodeAddress.ID_BROADCAST,
                bytes = VoiceWire.encode(packet),
                dataType = PortNum.PRIVATE_APP.value,
                channel = target.channelIndex,
                wantAck = wantAck && target.isDirect,
                hopLimit = if (prefs.loraRelay.value) 0 else DataPacket.HOP_LIMIT_NO_RELAY,
            )
        return try {
            commandSender.sendData(data)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w { "Voice LoRa send refused: ${e::class.simpleName}" }
            false
        }
    }

    /** Voice packets heard over LoRa (our own are left out). */
    fun incoming(myNodeNum: () -> Int): Flow<LoraVoiceIn> = serviceRepository.meshPacketFlow.mapNotNull { mesh ->
        val decoded = mesh.decoded ?: return@mapNotNull null
        if (decoded.portnum != PortNum.PRIVATE_APP || !VoiceWire.isVoice(decoded.payload)) return@mapNotNull null
        if (mesh.from == myNodeNum()) return@mapNotNull null
        val packet = VoiceWire.decode(decoded.payload) ?: return@mapNotNull null
        LoraVoiceIn(
            packet = packet,
            fromNum = mesh.from,
            channelIndex = if (mesh.pki_encrypted == true) NodeAddress.PKC_CHANNEL_INDEX else mesh.channel,
            broadcast = mesh.to == NodeAddress.NODENUM_BROADCAST,
        )
    }
}

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.Channel
import org.meshtastic.core.network.repository.NetworkMonitor
import org.meshtastic.core.network.repository.isPublicMqttServer
import org.meshtastic.core.network.repository.radioMqttClient
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.MqttMessage
import org.meshtastic.mqtt.QoS
import org.meshtastic.mqtt.packet.Subscription
import org.meshtastic.proto.ModuleConfig
import kotlin.concurrent.Volatile

/** Where a voice packet goes: a channel (by index) or one node, reached on channel [channelIndex]. */
data class VoiceTarget(val channelIndex: Int, val destNum: Int? = null) {
    val isDirect: Boolean
        get() = destNum != null
}

/** A voice packet that arrived over MQTT, with the conversation it belongs to. */
data class MqttVoiceIn(val packet: VoicePacket, val channelIndex: Int, val direct: Boolean)

/** What the voice MQTT link can do right now, for the UI. */
data class VoiceMqttStatus(
    val connected: Boolean = false,
    /** The server from the radio's MQTT settings, or null when MQTT is off there. */
    val serverAddress: String? = null,
    /** MQTT is turned off in the radio's settings, so voice will not use it. */
    val disabledInRadioSettings: Boolean = true,
    val networkAvailable: Boolean = false,
    /** Channels left out because their key is public and the server is the public broker. */
    val blockedPublicChannels: Set<Int> = emptySet(),
)

/**
 * Voice over the radio's MQTT server, independent of the radio and of the client proxy. It uses exactly the server,
 * login and TLS from the radio's MQTT settings (one place to enter a server), only while MQTT is enabled there and the
 * device has a network; payloads are AES-GCM encrypted per channel ([VoiceKeys]), and on the public broker a channel
 * with a well-known key is never used, so voice is not broadcast to everyone on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Single
class VoiceMqttLink(
    private val radioConfigRepository: RadioConfigRepository,
    private val networkMonitor: NetworkMonitor,
    private val identity: VoiceIdentity,
    private val applicationScope: ApplicationCoroutineScope,
) {
    private val cipher = platformVoiceCipher()

    private val statusFlow = MutableStateFlow(VoiceMqttStatus())
    val status: StateFlow<VoiceMqttStatus> = statusFlow.asStateFlow()

    private val incomingFlow =
        MutableSharedFlow<MqttVoiceIn>(
            extraBufferCapacity = INCOMING_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val incoming: SharedFlow<MqttVoiceIn> = incomingFlow.asSharedFlow()

    private class Session(
        val publish: suspend (MqttMessage) -> Unit,
        val root: String,
        val channels: Map<Int, Channel>,
        val allowedChannels: Set<Int>,
    )

    @Volatile private var session: Session? = null

    private data class Inputs(
        val mqtt: ModuleConfig.MQTTConfig?,
        val channels: Map<Int, Channel>,
        val network: Boolean,
        val myNum: Int,
    )

    private var started = false

    /** Starts following the radio's MQTT settings; idempotent. */
    fun start() {
        if (started) return
        started = true
        applicationScope.launch {
            combine(
                radioConfigRepository.moduleConfigFlow,
                radioConfigRepository.channelSetFlow,
                networkMonitor.networkAvailable,
                identity.nodeNum,
            ) { module, channelSet, network, myNum ->
                val lora = channelSet.lora_config ?: Channel.default.loraConfig
                Inputs(
                    mqtt = module.mqtt,
                    channels = channelSet.settings.mapIndexed { i, s -> i to Channel(s, lora) }.toMap(),
                    network = network,
                    myNum = myNum,
                )
            }
                .distinctUntilChanged()
                .collectLatest { inputs -> runSession(inputs) }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun runSession(inputs: Inputs) {
        val mqtt = inputs.mqtt
        val enabled = mqtt?.enabled == true
        val base =
            VoiceMqttStatus(
                serverAddress = mqtt?.address?.takeIf { enabled },
                disabledInRadioSettings = !enabled,
                networkAvailable = inputs.network,
            )
        statusFlow.value = base
        session = null
        if (!enabled || !inputs.network || inputs.myNum == 0 || cipher == null) return

        val radioClient = radioMqttClient("MeshtasticVoice-${VoiceMqttTopics.nodeId(inputs.myNum)}", mqtt)
        val publicServer = isPublicMqttServer(radioClient.address)
        val blocked = inputs.channels.filterValues { publicServer && VoiceKeys.isPublic(it) }.keys
        val allowed = inputs.channels.keys - blocked
        val root = VoiceMqttTopics.root(mqtt.root)
        val client = radioClient.client
        val topics =
            allowed.associateBy { VoiceMqttTopics.channel(root, inputs.channels.getValue(it)) } +
                // Direct messages are keyed with the primary channel; on the public broker that key must be private.
                (if (0 in allowed) mapOf(VoiceMqttTopics.direct(root, inputs.myNum) to DIRECT) else emptyMap())
        statusFlow.value = base.copy(serverAddress = radioClient.address, blockedPublicChannels = blocked)
        try {
            coroutineScope {
                launch {
                    client.messages.collect { msg -> handle(msg.topic, msg.payload.toByteArray(), topics, inputs) }
                }
                launch {
                    client.connectionState.collect { state ->
                        statusFlow.value = statusFlow.value.copy(connected = state is ConnectionState.Connected)
                    }
                }
                client.connect(radioClient.endpoint)
                if (topics.isNotEmpty()) {
                    client.subscribe(topics.keys.map { Subscription(topicFilter = it, maxQos = QoS.AT_LEAST_ONCE) })
                }
                session = Session({ client.publish(it) }, root, inputs.channels, allowed)
                Logger.i { "Voice MQTT connected (${topics.size} topics)" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w { "Voice MQTT session ended: ${e::class.simpleName}" }
        } finally {
            session = null
            statusFlow.value = statusFlow.value.copy(connected = false)
            withContext(NonCancellable) { runCatching { client.close() } }
        }
    }

    @Suppress("ReturnCount")
    private fun handle(topic: String, payload: ByteArray, topics: Map<String, Int>, inputs: Inputs) {
        val index = topics[topic] ?: return
        val keyChannel = inputs.channels[if (index == DIRECT) 0 else index] ?: return
        val plain = cipher?.open(VoiceKeys.channelKey(keyChannel), payload.toByteString(), topic.encodeUtf8()) ?: return
        val packet = VoiceWire.decode(plain) ?: return
        if (packet.origin == inputs.myNum) return // the broker echoes our own publishes
        incomingFlow.tryEmit(
            MqttVoiceIn(
                packet = packet.copy(viaMqtt = true),
                channelIndex = if (index == DIRECT) 0 else index,
                direct = index == DIRECT,
            ),
        )
    }

    /** True when [target] can go over MQTT right now. */
    fun canSend(target: VoiceTarget): Boolean {
        val s = session ?: return false
        return statusFlow.value.connected && (if (target.isDirect) 0 else target.channelIndex) in s.allowedChannels
    }

    /** Publishes one voice packet; false when MQTT is not usable for [target] right now. */
    @Suppress("ReturnCount")
    suspend fun send(target: VoiceTarget, packet: VoicePacket, reliable: Boolean): Boolean {
        val s = session ?: return false
        val cipher = cipher ?: return false
        if (!canSend(target)) return false
        val keyIndex = if (target.isDirect) 0 else target.channelIndex
        val channel = s.channels[keyIndex] ?: return false
        val topic =
            target.destNum?.let { VoiceMqttTopics.direct(s.root, it) } ?: VoiceMqttTopics.channel(s.root, channel)
        val sealed = cipher.seal(VoiceKeys.channelKey(channel), VoiceWire.encode(packet), topic.encodeUtf8())
        return try {
            s.publish(
                MqttMessage(
                    topic = topic,
                    payload = sealed.toByteArray(),
                    qos = if (reliable) QoS.AT_LEAST_ONCE else QoS.AT_MOST_ONCE,
                    retain = false,
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w { "Voice MQTT publish failed: ${e::class.simpleName}" }
            false
        }
    }

    private companion object {
        const val DIRECT = -1
        const val INCOMING_BUFFER = 64
    }
}

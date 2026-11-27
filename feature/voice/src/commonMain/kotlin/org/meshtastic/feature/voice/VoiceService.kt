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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.codec2.Codec2
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PersistedPacketId
import org.meshtastic.core.repository.VoicePrefs
import org.meshtastic.proto.PortNum
import kotlin.random.Random

/** Someone talking live right now. */
data class LiveTalker(val contactKey: String, val origin: Int, val name: String, val viaMqtt: Boolean)

/** Why a note could not be sent. */
enum class VoiceSendResult {
    SENT,

    /** Nothing can carry it right now; it waits in the outbox and goes when MQTT or the radio comes back. */
    QUEUED,

    /** The outbox is full. */
    REJECTED,

    /** Too long for LoRa on this preset, and MQTT is not available. */
    TOO_SLOW_FOR_LORA,

    /** No radio is connected, so nothing can go over LoRa. */
    NO_RADIO,

    /** This conversation cannot take voice (archived, or no identity yet). */
    UNSUPPORTED,
}

/**
 * The voice feature's engine: routes voice notes and push-to-talk over MQTT or LoRa ([VoiceRouter]), keeps a small
 * outbox for notes nothing can carry yet, rebuilds and stores incoming notes, forwards MQTT notes onto LoRa when this
 * device is a gateway, and plays incoming push-to-talk. Application-scoped, so a note keeps sending after the screen
 * closes.
 */
@Single
@Suppress("TooManyFunctions", "LongParameterList")
class VoiceService(
    private val mqtt: VoiceMqttLink,
    private val lora: VoiceLoraLink,
    private val identity: VoiceIdentity,
    private val prefs: VoicePrefs,
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val applicationScope: ApplicationCoroutineScope,
    private val dispatchers: CoroutineDispatchers,
) {
    private val audio = platformVoiceAudio()

    /** Codec2, AES-GCM and audio I/O all exist on this platform. */
    val supported: Boolean
        get() = Codec2.isAvailable && audio.available

    val mqttStatus: StateFlow<VoiceMqttStatus>
        get() = mqtt.status

    val radioReady: StateFlow<Boolean>
        get() = lora.radioReady

    private val talkersFlow = MutableStateFlow<Map<String, LiveTalker>>(emptyMap())
    val talkers: StateFlow<Map<String, LiveTalker>> = talkersFlow.asStateFlow()

    private val reassemblyLock = Mutex()
    private val reassembler = VoiceReassembler()
    private val sendLock = Mutex()

    private data class Outgoing(val rowId: PersistedPacketId, val clip: VoiceClip, val target: VoiceTarget)

    private val outbox = ArrayDeque<Outgoing>()
    private val forwarded = LinkedHashSet<Pair<Int, Int>>()
    private var started = false

    val codec: VoiceCodec
        get() = VoiceCodec.fromWireId(prefs.codecWireId.value) ?: VoiceCodec.DEFAULT

    val maxNoteSeconds: Int
        get() = prefs.maxNoteSeconds.value.coerceIn(MIN_NOTE_SECONDS, MAX_NOTE_SECONDS)

    private val links: VoiceLinks
        get() = VoiceLinks(mqttReady = mqtt.status.value.connected, radioReady = lora.radioReady.value)

    private val myNum: Int
        get() = identity.nodeNum.value

    /** Starts listening and keeps the MQTT link following the radio's settings; idempotent. */
    fun start() {
        if (started || !supported) return
        started = true
        mqtt.start()
        applicationScope.launch { lora.incoming { myNum }.collect { onLora(it) } }
        applicationScope.launch { mqtt.incoming.collect { onMqtt(it) } }
        applicationScope.launch {
            while (isActive) {
                delay(EXPIRY_TICK_MS)
                val expired = reassemblyLock.withLock {
                    reassembler.expire(nowMillis).map { clip ->
                        clip to (pendingContacts.remove(clip.origin to clip.clipId) ?: contactFor(clip.origin, null))
                    }
                }
                expired.forEach { (clip, contact) -> store(clip, contact) }
            }
        }
        applicationScope.launch {
            combine(mqtt.status, lora.radioReady) { m, r -> m.connected to r }
                .distinctUntilChanged()
                .collect { drainOutbox() }
        }
    }

    /** Where voice for [contactKey] goes; null for an archived conversation. */
    @Suppress("ReturnCount")
    fun targetOf(contactKey: String): VoiceTarget? {
        val key = ContactKey(contactKey)
        if (key.isRetired) return null
        val channel = key.channelOrNull ?: return null
        return when (val address = key.address) {
            NodeAddress.Broadcast -> VoiceTarget(channel)
            is NodeAddress.ByNum -> VoiceTarget(channel, address.num)
            else -> NodeAddress.idToNum(key.addressString)?.let { VoiceTarget(channel, it) }
        }
    }

    fun liveRoute(contactKey: String): LiveRoute {
        val target = targetOf(contactKey) ?: return LiveRoute.Unavailable(LiveUnavailableReason.NO_LINK)
        val mqttReady = mqtt.canSend(target)
        return VoiceRouter.live(
            VoiceLinks(mqttReady = mqttReady, radioReady = lora.radioReady.value),
            if (mqttReady) null else lora.liveBudget(codec),
        )
    }

    // ---- voice notes ----------------------------------------------------------------------------------------------

    /** Sends a recorded note ([frames] are whole frames of [codec]) and stores it in the conversation. */
    @Suppress("ReturnCount")
    suspend fun sendNote(contactKey: String, codec: VoiceCodec, frames: ByteString): VoiceSendResult {
        val target = targetOf(contactKey) ?: return VoiceSendResult.UNSUPPORTED
        if (myNum == 0 || frames.size == 0) return VoiceSendResult.UNSUPPORTED
        val clip = VoiceClip.fromFrames(myNum, Random.nextInt(), codec, frames)
        val rowId = saveOwn(contactKey, target, clip)
        return deliver(Outgoing(rowId, clip, target), allowQueue = true)
    }

    /** Re-sends a stored note onto LoRa in the same conversation (the bubble's "send over LoRa"). */
    @Suppress("ReturnCount")
    suspend fun forwardToLora(contactKey: String, stored: ByteString): VoiceSendResult {
        val target = targetOf(contactKey) ?: return VoiceSendResult.UNSUPPORTED
        val clip = VoiceClip.fromStored(stored) ?: return VoiceSendResult.UNSUPPORTED
        if (!lora.radioReady.value) return VoiceSendResult.NO_RADIO
        if (lora.noteBudget(clip) is LoraVoiceBudget.TooSlow) return VoiceSendResult.TOO_SLOW_FOR_LORA
        applicationScope.launch { sendOverLora(target, clip.copy(forwarded = true)) }
        return VoiceSendResult.SENT
    }

    @Suppress("ReturnCount")
    private suspend fun deliver(out: Outgoing, allowQueue: Boolean): VoiceSendResult {
        val transport = runCatching {
            VoiceNoteTransport.valueOf(prefs.noteTransport.value)
        }
            .getOrDefault(VoiceNoteTransport.AUTO)
        val mqttReady = mqtt.canSend(out.target)
        var route = VoiceRouter.note(VoiceLinks(mqttReady, lora.radioReady.value), transport)
        if (route is NoteRoute.Send && route.lora && lora.noteBudget(out.clip) is LoraVoiceBudget.TooSlow) {
            route = if (route.mqtt) route.copy(lora = false) else NoteRoute.Queue
            if (route == NoteRoute.Queue && !mqttReady) {
                packetRepository.updateMessageStatus(out.rowId, MessageStatus.ERROR)
                return VoiceSendResult.TOO_SLOW_FOR_LORA
            }
        }
        when (route) {
            NoteRoute.Queue -> {
                if (!allowQueue) return VoiceSendResult.QUEUED
                return sendLock.withLock {
                    if (outbox.size >= MAX_OUTBOX) {
                        packetRepository.updateMessageStatus(out.rowId, MessageStatus.ERROR)
                        VoiceSendResult.REJECTED
                    } else {
                        outbox.addLast(out)
                        VoiceSendResult.QUEUED
                    }
                }
            }

            is NoteRoute.Send -> {
                packetRepository.updateMessageStatus(out.rowId, MessageStatus.ENROUTE)
                applicationScope.launch {
                    var ok = false
                    if (route.mqtt) ok = mqtt.send(out.target, out.clip.wholePacket(), reliable = true) || ok
                    if (route.lora) ok = sendOverLora(out.target, out.clip) || ok
                    packetRepository.updateMessageStatus(
                        out.rowId,
                        if (ok) MessageStatus.DELIVERED else MessageStatus.ERROR,
                    )
                }
                return VoiceSendResult.SENT
            }
        }
    }

    private suspend fun drainOutbox() {
        val pending = sendLock.withLock { outbox.toList().also { outbox.clear() } }
        pending.forEach { deliver(it, allowQueue = true) }
    }

    /** Sends every chunk, spaced by its airtime so the radio's small queue never overflows. */
    private suspend fun sendOverLora(target: VoiceTarget, clip: VoiceClip): Boolean = sendLock.withLock {
        var sent = 0
        for (packet in clip.loraPackets()) {
            var attempts = 0
            while (!lora.send(target, packet, wantAck = true)) {
                if (++attempts > MAX_CHUNK_ATTEMPTS || !lora.radioReady.value) return@withLock sent > 0
                delay(QUEUE_BACKOFF_MS * attempts)
            }
            sent++
            delay((lora.packetMs(packet) * PACING_FACTOR).toLong())
        }
        true
    }

    private suspend fun saveOwn(contactKey: String, target: VoiceTarget, clip: VoiceClip): PersistedPacketId =
        packetRepository.savePacket(
            myNodeNum = myNum,
            contactKey = contactKey,
            packet =
            DataPacket(
                to = target.destNum?.let(NodeAddress::numToDefaultId) ?: NodeAddress.ID_BROADCAST,
                bytes = clip.toStored(),
                dataType = PortNum.AUDIO_APP.value,
                id = clip.clipId,
                status = MessageStatus.QUEUED,
                channel = target.channelIndex,
            ),
            receivedTime = nowMillis,
            read = true,
        )

    private suspend fun store(clip: VoiceClip, contactKey: String) {
        val broadcast = contactKey.endsWith(NodeAddress.ID_BROADCAST)
        val channel = ContactKey(contactKey).channelOrNull ?: 0
        packetRepository.insert(
            packet =
            DataPacket(
                to = if (broadcast) NodeAddress.ID_BROADCAST else NodeAddress.numToDefaultId(myNum),
                bytes = clip.toStored(),
                dataType = PortNum.AUDIO_APP.value,
                from = NodeAddress.numToDefaultId(clip.origin),
                id = clip.clipId,
                status = MessageStatus.RECEIVED,
                channel = channel,
                viaMqtt = clip.viaMqtt,
            ),
            myNodeNum = myNum,
            contactKey = contactKey,
            receivedTime = nowMillis,
            read = false,
        )
    }

    /** The conversation a note from [origin] belongs to: a channel, or the DM thread with that node. */
    private fun contactFor(origin: Int, broadcastChannel: Int?): String {
        broadcastChannel?.let {
            return "$it${NodeAddress.ID_BROADCAST}"
        }
        // A DM goes to the thread the app already uses with that node: PKI (8) when both ends have keys.
        val node = nodeRepository.nodeDBbyNum.value[origin]
        val channel = if (node?.hasPKC == true) NodeAddress.PKC_CHANNEL_INDEX else 0
        return "$channel${NodeAddress.numToDefaultId(origin)}"
    }

    // Which conversation each half-built note belongs to, remembered from its first chunk.
    private val pendingContacts = LinkedHashMap<Pair<Int, Int>, String>()

    private suspend fun onNotePacket(packet: VoicePacket, contactKey: String) {
        val key = packet.origin to packet.streamId
        val clip =
            reassemblyLock.withLock {
                pendingContacts.getOrPut(key) { contactKey }
                while (pendingContacts.size > MAX_PENDING_CONTACTS) pendingContacts.remove(pendingContacts.keys.first())
                reassembler.accept(packet, nowMillis)
            } ?: return
        val contact = reassemblyLock.withLock { pendingContacts.remove(key) } ?: contactKey
        store(clip, contact)
        if (clip.viaMqtt && !clip.forwarded) maybeGateway(clip, contact)
    }

    private suspend fun onLora(input: LoraVoiceIn) {
        val contact =
            if (input.broadcast) {
                contactFor(input.packet.origin, input.channelIndex)
            } else {
                "${input.channelIndex}${NodeAddress.numToDefaultId(input.packet.origin)}"
            }
        dispatch(input.packet, contact)
    }

    private suspend fun onMqtt(input: MqttVoiceIn) {
        val contact = contactFor(input.packet.origin, if (input.direct) null else input.channelIndex)
        dispatch(input.packet, contact)
    }

    private suspend fun dispatch(packet: VoicePacket, contactKey: String) {
        when (packet.type) {
            VoicePacketType.NOTE_CHUNK,
            VoicePacketType.NOTE_WHOLE,
            VoicePacketType.NOTE_ABORT,
            -> onNotePacket(packet, contactKey)

            VoicePacketType.LIVE_FRAMES,
            VoicePacketType.LIVE_END,
            -> onLivePacket(packet, contactKey)
        }
    }

    /** A gateway re-sends channel notes heard over MQTT onto the local mesh, once, if LoRa can afford it. */
    @Suppress("ReturnCount")
    private fun maybeGateway(clip: VoiceClip, contactKey: String) {
        if (!prefs.gatewayEnabled.value || !lora.radioReady.value) return
        if (!contactKey.endsWith(NodeAddress.ID_BROADCAST)) return
        val key = clip.origin to clip.clipId
        if (!forwarded.add(key)) return
        while (forwarded.size > MAX_FORWARD_MEMORY) forwarded.remove(forwarded.first())
        if (lora.noteBudget(clip) !is LoraVoiceBudget.Ok) {
            Logger.i { "Voice gateway skipped a note: LoRa budget" }
            return
        }
        val target = targetOf(contactKey) ?: return
        applicationScope.launch { sendOverLora(target, clip.copy(forwarded = true)) }
    }

    // ---- push-to-talk out ---------------------------------------------------------------------------------------

    /** One outgoing push-to-talk burst. */
    inner class LiveTx
    internal constructor(
        val route: LiveRoute,
        private val target: VoiceTarget,
        val codec: VoiceCodec,
    ) {
        private val streamId = Random.nextInt()
        private var seq = 0

        /** Frames per packet: short over MQTT for low delay, as many as fit over LoRa. */
        val framesPerPacket: Int =
            if (route == LiveRoute.Mqtt) (MQTT_LIVE_PACKET_MS / codec.frameMs) else VoiceWire.loraFramesPerPacket(codec)

        suspend fun send(frames: ByteString) {
            val packet = VoicePacket(VoicePacketType.LIVE_FRAMES, codec, myNum, streamId, seq++, 0, frames)
            emit(packet)
        }

        suspend fun end() {
            emit(VoicePacket(VoicePacketType.LIVE_END, codec, myNum, streamId, seq, 0, ByteString.EMPTY, last = true))
        }

        private suspend fun emit(packet: VoicePacket) {
            when (route) {
                LiveRoute.Mqtt -> mqtt.send(target, packet, reliable = false)
                LiveRoute.Lora -> lora.send(target, packet, wantAck = false)
                is LiveRoute.Unavailable -> Unit
            }
        }
    }

    /** Opens a burst to [contactKey], or null with the reason when live audio cannot go anywhere now. */
    @Suppress("ReturnCount")
    fun beginLive(contactKey: String): Pair<LiveTx?, LiveRoute> {
        val target = targetOf(contactKey) ?: return null to LiveRoute.Unavailable(LiveUnavailableReason.NO_LINK)
        if (myNum == 0) return null to LiveRoute.Unavailable(LiveUnavailableReason.NO_LINK)
        val route = liveRoute(contactKey)
        return if (route is LiveRoute.Unavailable) null to route else LiveTx(route, target, codec) to route
    }

    // ---- push-to-talk in ----------------------------------------------------------------------------------------

    private val liveLock = Mutex()
    private var liveStream: Pair<Int, Int>? = null
    private var liveBuffer: LiveJitterBuffer? = null
    private var liveJob: Job? = null

    private suspend fun onLivePacket(packet: VoicePacket, contactKey: String) {
        val key = packet.origin to packet.streamId
        liveLock.withLock {
            if (liveStream == key) {
                liveBuffer?.push(packet, nowMillis)
                return
            }
            if (liveJob?.isActive == true || packet.type == VoicePacketType.LIVE_END) return // one talker at a time
            val buffer =
                LiveJitterBuffer(packet.codec, if (packet.viaMqtt) MQTT_JITTER_MS else LORA_JITTER_MS).also {
                    it.push(packet, nowMillis)
                }
            liveStream = key
            liveBuffer = buffer
            val name =
                nodeRepository.nodeDBbyNum.value[packet.origin]?.user?.long_name
                    ?: NodeAddress.numToDefaultId(packet.origin)
            talkersFlow.update { it + (contactKey to LiveTalker(contactKey, packet.origin, name, packet.viaMqtt)) }
            liveJob = applicationScope.launch(dispatchers.io) { playLive(buffer, packet.codec, contactKey) }
        }
    }

    @Suppress("NestedBlockDepth")
    private suspend fun playLive(buffer: LiveJitterBuffer, codec: VoiceCodec, contactKey: String) {
        val play = prefs.autoplayLive.value
        try {
            Codec2.open(codec.mode).use { c2 ->
                val out = if (play) audio.openOutput() else null
                out.use {
                    while (true) {
                        val next = liveLock.withLock { buffer.pull(nowMillis) }
                        when (next) {
                            is LiveJitterBuffer.Next.Audio -> out?.write(c2.decode(next.frames.toByteArray()))

                            is LiveJitterBuffer.Next.Silence ->
                                out?.write(ShortArray(next.frames * codec.samplesPerFrame))

                            LiveJitterBuffer.Next.Waiting -> delay(LIVE_POLL_MS)

                            LiveJitterBuffer.Next.Ended -> {
                                out?.drain()
                                break
                            }
                        }
                        if (out == null && next !is LiveJitterBuffer.Next.Waiting) delay(codec.frameMs.toLong())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w { "Live voice playback stopped: ${e::class.simpleName}" }
        } finally {
            liveLock.withLock {
                liveStream = null
                liveBuffer = null
            }
            talkersFlow.update { it - contactKey }
        }
    }

    // ---- local playback and recording -----------------------------------------------------------------------------

    val audioIo: VoiceAudio
        get() = audio

    private companion object {
        const val MIN_NOTE_SECONDS = 5
        const val MAX_NOTE_SECONDS = 120
        const val MAX_OUTBOX = 3
        const val MAX_CHUNK_ATTEMPTS = 5
        const val QUEUE_BACKOFF_MS = 1_000L
        const val PACING_FACTOR = 1.5
        const val EXPIRY_TICK_MS = 5_000L
        const val MAX_FORWARD_MEMORY = 256
        const val MAX_PENDING_CONTACTS = 16
        const val MQTT_LIVE_PACKET_MS = 200
        const val MQTT_JITTER_MS = 300
        const val LORA_JITTER_MS = 1_200
        const val LIVE_POLL_MS = 20L
    }
}

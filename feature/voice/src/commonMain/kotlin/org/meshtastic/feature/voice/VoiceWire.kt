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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.voice

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.codec2.Codec2Mode

/**
 * The codecs on the wire. [wireId] is this protocol's own stable number, independent of the native library's mode
 * values; [samplesPerFrame] and [bytesPerFrame] are Codec2 1.2.0's frame shapes (checked against the native library in
 * the JVM tests), so pure code can size packets without loading native code.
 */
enum class VoiceCodec(val wireId: Int, val mode: Codec2Mode, val samplesPerFrame: Int, val bytesPerFrame: Int) {
    C2_700C(1, Codec2Mode.MODE_700C, 320, 4),
    C2_1200(2, Codec2Mode.MODE_1200, 320, 6),
    C2_1300(3, Codec2Mode.MODE_1300, 320, 7),
    C2_1400(4, Codec2Mode.MODE_1400, 320, 7),
    C2_1600(5, Codec2Mode.MODE_1600, 320, 8),
    C2_2400(6, Codec2Mode.MODE_2400, 160, 6),
    C2_3200(7, Codec2Mode.MODE_3200, 160, 8),
    ;

    /** Milliseconds of audio in one frame (8 kHz sample rate). */
    val frameMs: Int
        get() = samplesPerFrame / 8

    val bitsPerSecond: Int
        get() = mode.bitsPerSecond

    companion object {
        val DEFAULT: VoiceCodec = C2_1300

        fun fromWireId(id: Int): VoiceCodec? = entries.firstOrNull { it.wireId == id }
    }
}

enum class VoicePacketType(val wireId: Int) {
    /** One chunk of a voice note sent over LoRa; [VoicePacket.total] is the chunk count. */
    NOTE_CHUNK(1),

    /** A whole voice note in one packet (MQTT has no 233-byte limit). */
    NOTE_WHOLE(2),

    /** Live push-to-talk audio; [VoicePacket.seq] counts packets in the stream. */
    LIVE_FRAMES(3),

    /** The talker released push-to-talk. */
    LIVE_END(4),

    /** The sender cancelled a voice note part-way; drop what has arrived. */
    NOTE_ABORT(5),
    ;

    companion object {
        fun fromWireId(id: Int): VoicePacketType? = entries.firstOrNull { it.wireId == id }
    }
}

/**
 * One voice packet, identical on LoRa and MQTT. [origin] is the node that recorded the audio and stays the same when a
 * gateway forwards it; [streamId] identifies one note or one push-to-talk burst from that origin.
 */
data class VoicePacket(
    val type: VoicePacketType,
    val codec: VoiceCodec,
    val origin: Int,
    val streamId: Int,
    val seq: Int,
    val total: Int,
    val frames: ByteString,
    val last: Boolean = false,
    /** A gateway re-sent this from MQTT onto LoRa; it is never forwarded again. */
    val forwarded: Boolean = false,
    /** The packet first travelled over MQTT. */
    val viaMqtt: Boolean = false,
) {
    val frameCount: Int
        get() = frames.size / codec.bytesPerFrame

    val durationMs: Int
        get() = frameCount * codec.frameMs
}

/**
 * Binary header shared by every voice packet:
 * ```
 * 0..3   magic "MVX1"
 * 4      type            5      flags (bit0 last, bit1 forwarded, bit2 via MQTT)
 * 6      codec wire id   7..10  origin node num (u32, big-endian)
 * 11..14 stream id (u32) 15..16 seq (u16)   17..18 total (u16)
 * 19..   whole Codec2 frames
 * ```
 */
object VoiceWire {
    val MAGIC: ByteString = "MVX1".encodeUtf8()
    const val HEADER_SIZE = 19

    /**
     * Largest voice packet handed to [org.meshtastic.core.repository.CommandSender.sendData]. Its 233-byte limit covers
     * the whole encoded `Data` message, and the portnum and payload fields add up to 6 bytes; the rest is margin.
     */
    const val LORA_MAX_PACKET = 216

    private const val FLAG_LAST = 0x01
    private const val FLAG_FORWARDED = 0x02
    private const val FLAG_VIA_MQTT = 0x04

    /** How many whole frames of [codec] fit in one LoRa packet. */
    fun loraFramesPerPacket(codec: VoiceCodec): Int = (LORA_MAX_PACKET - HEADER_SIZE) / codec.bytesPerFrame

    fun encode(packet: VoicePacket): ByteString {
        require(packet.frames.size % packet.codec.bytesPerFrame == 0) { "frames must be whole Codec2 frames" }
        var flags = 0
        if (packet.last) flags = flags or FLAG_LAST
        if (packet.forwarded) flags = flags or FLAG_FORWARDED
        if (packet.viaMqtt) flags = flags or FLAG_VIA_MQTT
        return Buffer()
            .write(MAGIC)
            .writeByte(packet.type.wireId)
            .writeByte(flags)
            .writeByte(packet.codec.wireId)
            .writeInt(packet.origin)
            .writeInt(packet.streamId)
            .writeShort(packet.seq)
            .writeShort(packet.total)
            .write(packet.frames)
            .readByteString()
    }

    /** Null for anything that is not a well-formed voice packet (other `PRIVATE_APP` users share the port). */
    @Suppress("ReturnCount")
    fun decode(bytes: ByteString): VoicePacket? {
        if (bytes.size < HEADER_SIZE || !bytes.rangeEquals(0, MAGIC, 0, MAGIC.size)) return null
        val buffer = Buffer().write(bytes)
        buffer.skip(MAGIC.size.toLong())
        val type = VoicePacketType.fromWireId(buffer.readByte().toInt() and 0xFF) ?: return null
        val flags = buffer.readByte().toInt() and 0xFF
        val codec = VoiceCodec.fromWireId(buffer.readByte().toInt() and 0xFF) ?: return null
        val origin = buffer.readInt()
        val streamId = buffer.readInt()
        val seq = buffer.readShort().toInt() and 0xFFFF
        val total = buffer.readShort().toInt() and 0xFFFF
        val frames = buffer.readByteString()
        if (frames.size % codec.bytesPerFrame != 0) return null
        return VoicePacket(
            type = type,
            codec = codec,
            origin = origin,
            streamId = streamId,
            seq = seq,
            total = total,
            frames = frames,
            last = flags and FLAG_LAST != 0,
            forwarded = flags and FLAG_FORWARDED != 0,
            viaMqtt = flags and FLAG_VIA_MQTT != 0,
        )
    }

    fun isVoice(bytes: ByteString): Boolean = bytes.size >= HEADER_SIZE && bytes.rangeEquals(0, MAGIC, 0, MAGIC.size)
}

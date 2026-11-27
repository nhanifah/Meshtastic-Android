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

/**
 * A voice note: the Codec2 frames in [chunks] order, with `null` for a chunk that never arrived. Every chunk but the
 * last holds [framesPerChunk] frames, so a missing chunk is played as that much silence and the timing of the rest is
 * kept.
 */
data class VoiceClip(
    val origin: Int,
    val clipId: Int,
    val codec: VoiceCodec,
    val framesPerChunk: Int,
    val chunks: List<ByteString?>,
    /** Arrived over MQTT (set by the receiver, not stored in [toStored]). */
    val viaMqtt: Boolean = false,
    /** Already re-sent from MQTT onto LoRa by a gateway. */
    val forwarded: Boolean = false,
) {
    val missingChunks: Int
        get() = chunks.count { it == null }

    val isComplete: Boolean
        get() = missingChunks == 0

    val frameCount: Int
        get() = chunks.sumOf { it?.let { c -> c.size / codec.bytesPerFrame } ?: framesPerChunk }

    val durationMs: Int
        get() = frameCount * codec.frameMs

    /** Every frame in order, with the frames of a missing chunk left out (the player inserts silence for them). */
    val segments: List<Segment>
        get() = chunks.map { it?.let(Segment::Audio) ?: Segment.Silence(framesPerChunk) }

    sealed interface Segment {
        data class Audio(val frames: ByteString) : Segment

        data class Silence(val frames: Int) : Segment
    }

    /** Splits the note into LoRa packets of at most [VoiceWire.LORA_MAX_PACKET] bytes. Missing chunks are skipped. */
    fun loraPackets(forwarded: Boolean = this.forwarded): List<VoicePacket> = chunks.mapIndexedNotNull { seq, chunk ->
        chunk?.let {
            VoicePacket(
                type = VoicePacketType.NOTE_CHUNK,
                codec = codec,
                origin = origin,
                streamId = clipId,
                seq = seq,
                total = chunks.size,
                frames = it,
                last = seq == chunks.lastIndex,
                forwarded = forwarded,
                viaMqtt = viaMqtt,
            )
        }
    }

    /**
     * The whole note as one MQTT packet. Missing chunks become silence frames' worth of nothing, so only send whole.
     */
    fun wholePacket(): VoicePacket {
        val frames = Buffer()
        chunks.forEach { it?.let(frames::write) }
        return VoicePacket(
            type = VoicePacketType.NOTE_WHOLE,
            codec = codec,
            origin = origin,
            streamId = clipId,
            seq = 0,
            total = 1,
            frames = frames.readByteString(),
            last = true,
            forwarded = forwarded,
            viaMqtt = true,
        )
    }

    /** The form kept in the message database (`Packet.data.bytes`, port `AUDIO_APP`). */
    fun toStored(): ByteString {
        val out =
            Buffer()
                .write(STORED_MAGIC)
                .writeByte(codec.wireId)
                .writeShort(framesPerChunk)
                .writeInt(origin)
                .writeInt(clipId)
                .writeShort(chunks.size)
        chunks.forEach { chunk ->
            if (chunk == null) {
                out.writeShort(MISSING)
            } else {
                out.writeShort(chunk.size)
                out.write(chunk)
            }
        }
        return out.readByteString()
    }

    companion object {
        val STORED_MAGIC: ByteString = "MVC1".encodeUtf8()
        private const val MISSING = 0xFFFF

        /** Cuts recorded [frames] into chunks that each fit one LoRa packet. */
        fun fromFrames(origin: Int, clipId: Int, codec: VoiceCodec, frames: ByteString): VoiceClip {
            require(frames.size % codec.bytesPerFrame == 0) { "frames must be whole Codec2 frames" }
            val perChunk = VoiceWire.loraFramesPerPacket(codec)
            val chunkBytes = perChunk * codec.bytesPerFrame
            val chunks =
                (0 until frames.size step chunkBytes).map { start ->
                    frames.substring(start, minOf(start + chunkBytes, frames.size))
                }
            return VoiceClip(origin, clipId, codec, perChunk, chunks.ifEmpty { listOf(ByteString.EMPTY) })
        }

        @Suppress("ReturnCount")
        fun fromStored(bytes: ByteString): VoiceClip? {
            if (bytes.size < STORED_MAGIC.size + 13 || !bytes.rangeEquals(0, STORED_MAGIC, 0, STORED_MAGIC.size)) {
                return null
            }
            return runCatching {
                val buffer = Buffer().write(bytes)
                buffer.skip(STORED_MAGIC.size.toLong())
                val codec = VoiceCodec.fromWireId(buffer.readByte().toInt() and 0xFF) ?: return null
                val perChunk = buffer.readShort().toInt() and 0xFFFF
                val origin = buffer.readInt()
                val clipId = buffer.readInt()
                val count = buffer.readShort().toInt() and 0xFFFF
                val chunks =
                    List(count) {
                        val len = buffer.readShort().toInt() and 0xFFFF
                        if (len == MISSING) null else buffer.readByteString(len.toLong())
                    }
                VoiceClip(origin, clipId, codec, perChunk, chunks)
            }
                .getOrNull()
        }

        fun isStored(bytes: ByteString): Boolean =
            bytes.size >= STORED_MAGIC.size && bytes.rangeEquals(0, STORED_MAGIC, 0, STORED_MAGIC.size)
    }
}

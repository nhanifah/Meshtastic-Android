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

import okio.ByteString

/**
 * Puts voice notes back together from [VoicePacketType.NOTE_CHUNK]s that arrive out of order, twice (relays, a gateway
 * and the broker can all deliver the same chunk), or not at all. Bounded: at most [maxPending] half-built notes and
 * [maxClipBytes] per note; a note is given up [idleTimeoutMs] after its last chunk (or [afterLastChunkMs] once the
 * final chunk has been seen) and handed over with its gaps.
 *
 * Not thread-safe; one collector owns it.
 */
class VoiceReassembler(
    private val maxPending: Int = 8,
    private val maxClipBytes: Int = 64 * 1024,
    private val idleTimeoutMs: Long = 120_000,
    private val afterLastChunkMs: Long = 15_000,
    private val maxChunks: Int = 512,
    private val rememberFinished: Int = 128,
) {
    private data class Key(val origin: Int, val streamId: Int)

    private class Pending(
        val codec: VoiceCodec,
        val total: Int,
        val viaMqtt: Boolean,
        val forwarded: Boolean,
        var lastActivity: Long,
    ) {
        val chunks = arrayOfNulls<ByteString>(total)
        var bytes = 0
        var sawLast = false
    }

    private val pending = LinkedHashMap<Key, Pending>()

    // Insertion-ordered so the oldest finished note is forgotten first.
    private val finished = LinkedHashSet<Key>()

    val pendingCount: Int
        get() = pending.size

    /** Feeds one packet; returns a note when this packet completed one. Other packet types are ignored. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun accept(packet: VoicePacket, nowMs: Long): VoiceClip? {
        val key = Key(packet.origin, packet.streamId)
        if (key in finished) return null
        when (packet.type) {
            VoicePacketType.NOTE_WHOLE -> {
                markFinished(key)
                pending.remove(key)
                return VoiceClip.fromFrames(packet.origin, packet.streamId, packet.codec, packet.frames)
                    .copy(viaMqtt = packet.viaMqtt, forwarded = packet.forwarded)
            }

            VoicePacketType.NOTE_ABORT -> {
                pending.remove(key)
                markFinished(key)
                return null
            }

            VoicePacketType.NOTE_CHUNK -> Unit

            else -> return null
        }
        if (packet.total !in 1..maxChunks || packet.seq !in 0 until packet.total) return null
        val note =
            pending[key]
                ?: Pending(packet.codec, packet.total, packet.viaMqtt, packet.forwarded, nowMs).also {
                    if (pending.size >= maxPending) pending.remove(pending.keys.first())
                    pending[key] = it
                }
        if (note.codec != packet.codec || note.total != packet.total) return null
        note.lastActivity = nowMs
        if (packet.last || packet.seq == packet.total - 1) note.sawLast = true
        if (note.chunks[packet.seq] == null) {
            if (note.bytes + packet.frames.size > maxClipBytes) return null
            note.chunks[packet.seq] = packet.frames
            note.bytes += packet.frames.size
        }
        if (note.chunks.all { it != null }) {
            pending.remove(key)
            markFinished(key)
            return note.toClip(key)
        }
        return null
    }

    /** Gives up on notes that stopped arriving; each is returned with its gaps (if any chunk arrived at all). */
    fun expire(nowMs: Long): List<VoiceClip> {
        val expired = pending.filter { (_, note) ->
            val limit = if (note.sawLast) afterLastChunkMs else idleTimeoutMs
            nowMs - note.lastActivity >= limit
        }
        return expired.mapNotNull { (key, note) ->
            pending.remove(key)
            markFinished(key)
            note.toClip(key).takeIf { it.chunks.any { c -> c != null } }
        }
    }

    private fun markFinished(key: Key) {
        finished.remove(key)
        finished.add(key)
        while (finished.size > rememberFinished) finished.remove(finished.first())
    }

    private fun Pending.toClip(key: Key) = VoiceClip(
        origin = key.origin,
        clipId = key.streamId,
        codec = codec,
        framesPerChunk = VoiceWire.loraFramesPerPacket(codec),
        chunks = chunks.toList(),
        viaMqtt = viaMqtt,
        forwarded = forwarded,
    )
}

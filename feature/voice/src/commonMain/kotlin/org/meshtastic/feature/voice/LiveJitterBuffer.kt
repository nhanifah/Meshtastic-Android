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
 * Smooths one push-to-talk stream: packets go in as they arrive ([push]), and the player takes the next stretch of
 * audio ([pull]) whenever it has played the previous one. Playback starts once [targetDelayMs] of audio is buffered, a
 * lost packet is played as silence as soon as a later one is waiting, and if more than [maxDelayMs] piles up the oldest
 * audio is dropped so the listener does not fall further and further behind.
 *
 * Not thread-safe; the player owns it.
 */
class LiveJitterBuffer(
    val codec: VoiceCodec,
    private val targetDelayMs: Int,
    private val maxDelayMs: Int = targetDelayMs * 4,
    private val silenceTimeoutMs: Long = 3_000,
) {
    sealed interface Next {
        data class Audio(val frames: ByteString) : Next

        data class Silence(val frames: Int) : Next

        /** Nothing to play yet; ask again shortly. */
        data object Waiting : Next

        /** The talker released, or went quiet for too long. */
        data object Ended : Next
    }

    private val packets = HashMap<Int, ByteString>()
    private var nextSeq = -1
    private var endSeq = -1
    private var started = false
    private var lastPushMs = 0L
    private var lastFrames = 0

    val bufferedMs: Int
        get() = packets.values.sumOf { it.size / codec.bytesPerFrame } * codec.frameMs

    @Suppress("ReturnCount")
    fun push(packet: VoicePacket, nowMs: Long) {
        if (packet.codec != codec) return
        lastPushMs = nowMs
        if (packet.type == VoicePacketType.LIVE_END) {
            endSeq = packet.seq
            return
        }
        if (packet.type != VoicePacketType.LIVE_FRAMES) return
        if (nextSeq == -1) nextSeq = packet.seq
        if (packet.seq < nextSeq) return // late: its slot was already played as silence
        packets[packet.seq] = packet.frames
        while (bufferedMs > maxDelayMs && packets.isNotEmpty()) {
            nextSeq = packets.keys.min()
            packets.remove(nextSeq)
            nextSeq++
        }
    }

    @Suppress("ReturnCount")
    fun pull(nowMs: Long): Next {
        if (endSeq != -1 && (nextSeq == -1 || nextSeq >= endSeq) && packets.isEmpty()) return Next.Ended
        if (nowMs - lastPushMs >= silenceTimeoutMs && packets.isEmpty()) return Next.Ended
        if (nextSeq == -1) return Next.Waiting
        if (!started) {
            if (bufferedMs < targetDelayMs && endSeq == -1) return Next.Waiting
            started = true
        }
        packets.remove(nextSeq)?.let { frames ->
            nextSeq++
            lastFrames = frames.size / codec.bytesPerFrame
            return Next.Audio(frames)
        }
        // The next packet is missing. Play its slot as silence only once a later one proves it is lost.
        if (packets.keys.any { it > nextSeq } || (endSeq != -1 && nextSeq < endSeq)) {
            nextSeq++
            return Next.Silence(lastFrames.coerceAtLeast(1))
        }
        return Next.Waiting
    }
}

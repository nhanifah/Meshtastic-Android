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

import kotlinx.coroutines.flow.Flow

/** Microphone and speaker at Codec2's rate: 8 kHz, mono, signed 16-bit PCM. */
interface VoiceAudio {
    /** False where there is no audio implementation (iOS for now). */
    val available: Boolean

    /**
     * Records until the collector is cancelled, emitting blocks of exactly [blockSamples] samples. Throws when the
     * microphone cannot be opened (no permission, or in use by another app).
     */
    fun record(blockSamples: Int): Flow<ShortArray>

    /** Opens the speaker; [VoiceAudioOut.write] blocks for roughly as long as the audio lasts, which paces playback. */
    fun openOutput(): VoiceAudioOut
}

interface VoiceAudioOut : AutoCloseable {
    fun write(pcm: ShortArray)

    /**
     * Waits until everything written has come out of the speaker. [close] stops at once and drops what is still
     * buffered, which on Android can be the whole of a short note, so call this first when playback ends normally.
     */
    suspend fun drain() {}
}

/** Loudness helpers for decoded speech. */
object VoiceLevel {
    private const val TARGET_PEAK = 22_000
    private const val MAX_GAIN = 6f
    private const val MIN_PEAK = 64

    /**
     * The gain that brings [pcm]'s peak up to a comfortable level, capped so near-silence is not blown up into hiss.
     * Never below 1: loud notes are left alone.
     */
    fun gainFor(pcm: List<ShortArray>): Float {
        var peak = 0
        for (block in pcm) {
            for (s in block) {
                val a = if (s < 0) -s.toInt() else s.toInt()
                if (a > peak) peak = a
            }
        }
        if (peak < MIN_PEAK) return 1f
        return (TARGET_PEAK.toFloat() / peak).coerceIn(1f, MAX_GAIN)
    }

    fun apply(pcm: ShortArray, gain: Float): ShortArray {
        if (gain == 1f) return pcm
        return ShortArray(pcm.size) { i ->
            (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

expect fun platformVoiceAudio(): VoiceAudio

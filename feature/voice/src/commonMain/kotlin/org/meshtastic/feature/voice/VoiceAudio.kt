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
}

expect fun platformVoiceAudio(): VoiceAudio

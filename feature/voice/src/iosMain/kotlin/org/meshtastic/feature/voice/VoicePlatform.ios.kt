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

// Voice is not built for iOS yet (no Codec2, no AES-GCM, no audio I/O here); everything reports unavailable.
actual fun platformVoiceCipher(): VoiceCipher? = null

private object NoVoiceAudio : VoiceAudio {
    override val available = false

    override fun record(blockSamples: Int): kotlinx.coroutines.flow.Flow<ShortArray> =
        throw UnsupportedOperationException("Voice is not built for iOS yet")

    override fun openOutput(): VoiceAudioOut = throw UnsupportedOperationException("Voice is not built for iOS yet")
}

actual fun platformVoiceAudio(): VoiceAudio = NoVoiceAudio

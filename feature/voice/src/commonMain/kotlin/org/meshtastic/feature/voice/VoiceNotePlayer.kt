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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.ByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.codec2.Codec2
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.di.CoroutineDispatchers

/** What the note player is doing; [note] identifies the stored note being played. */
data class NotePlayback(val note: ByteString? = null, val positionMs: Int = 0, val durationMs: Int = 0)

/** Plays one stored voice note at a time; starting another stops the first. */
@Single
class VoiceNotePlayer(private val scope: ApplicationCoroutineScope, private val dispatchers: CoroutineDispatchers) {
    private val audio = platformVoiceAudio()
    private val stateFlow = MutableStateFlow(NotePlayback())
    val state: StateFlow<NotePlayback> = stateFlow.asStateFlow()
    private var job: Job? = null

    fun toggle(stored: ByteString) {
        if (stateFlow.value.note == stored) stop() else play(stored)
    }

    fun stop() {
        job?.cancel()
        job = null
        stateFlow.value = NotePlayback()
    }

    private fun play(stored: ByteString) {
        stop()
        val clip = VoiceClip.fromStored(stored) ?: return
        if (!Codec2.isAvailable || !audio.available) return
        stateFlow.value = NotePlayback(stored, 0, clip.durationMs)
        job =
            scope.launch(dispatchers.io) {
                try {
                    Codec2.open(clip.codec.mode).use { c2 ->
                        audio.openOutput().use { out ->
                            var position = 0
                            for (segment in clip.segments) {
                                ensureActive()
                                val (pcm, frames) =
                                    when (segment) {
                                        is VoiceClip.Segment.Audio ->
                                            c2.decode(segment.frames.toByteArray()) to
                                                segment.frames.size / clip.codec.bytesPerFrame

                                        is VoiceClip.Segment.Silence ->
                                            ShortArray(segment.frames * clip.codec.samplesPerFrame) to segment.frames
                                    }
                                out.write(pcm)
                                position += frames * clip.codec.frameMs
                                stateFlow.value = NotePlayback(stored, position, clip.durationMs)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Logger.w { "Voice note playback failed: ${e::class.simpleName}" }
                } finally {
                    if (stateFlow.value.note == stored) stateFlow.value = NotePlayback()
                }
            }
    }
}

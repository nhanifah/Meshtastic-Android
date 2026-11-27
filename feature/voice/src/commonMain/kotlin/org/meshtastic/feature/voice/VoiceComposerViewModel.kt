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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.codec2.Codec2
import org.meshtastic.core.common.util.nowMillis

/** A short message for the composer: why a note waits, or why live audio is unavailable. */
enum class VoiceNotice {
    QUEUED,
    REJECTED,
    TOO_SLOW_FOR_LORA,
    NO_RADIO,
    UNSUPPORTED,
    FORWARDED,
    MIC_FAILED,
    LIVE_NO_LINK,
    LIVE_LORA_TOO_SLOW,
    LIVE_LORA_BUSY,
}

sealed interface ComposerVoiceState {
    data object Idle : ComposerVoiceState

    data class RecordingNote(val elapsedMs: Long, val maxMs: Long) : ComposerVoiceState

    data class Talking(val viaMqtt: Boolean, val elapsedMs: Long) : ComposerVoiceState
}

/** Records voice notes (tap to start, send or discard) and push-to-talk bursts (hold) for the conversation screen. */
@KoinViewModel
class VoiceComposerViewModel(private val service: VoiceService, private val player: VoiceNotePlayer) : ViewModel() {
    val supported: Boolean
        get() = service.supported

    private val stateFlow = MutableStateFlow<ComposerVoiceState>(ComposerVoiceState.Idle)
    val state: StateFlow<ComposerVoiceState> = stateFlow.asStateFlow()

    private val noticeFlow = MutableStateFlow<VoiceNotice?>(null)
    val notice: StateFlow<VoiceNotice?> = noticeFlow.asStateFlow()

    val talkers: StateFlow<Map<String, LiveTalker>>
        get() = service.talkers

    val playback: StateFlow<NotePlayback>
        get() = player.state

    val radioReady: StateFlow<Boolean>
        get() = service.radioReady

    private val lock = Mutex()
    private var job: Job? = null
    private var noteFrames = Buffer()
    private var noteCodec = VoiceCodec.DEFAULT
    private var live: VoiceService.LiveTx? = null

    fun clearNotice() {
        noticeFlow.value = null
    }

    fun togglePlay(stored: ByteString) = player.toggle(stored)

    fun forwardToLora(contactKey: String, stored: ByteString) {
        viewModelScope.launch { noticeFlow.value = service.forwardToLora(contactKey, stored).toNotice(forward = true) }
    }

    // ---- voice notes ----

    fun startNote(contactKey: String) {
        if (stateFlow.value != ComposerVoiceState.Idle || !supported) return
        player.stop()
        val codec = service.codec
        val maxMs = service.maxNoteSeconds * MILLIS
        noteCodec = codec
        noteFrames = Buffer()
        val started = nowMillis
        stateFlow.value = ComposerVoiceState.RecordingNote(0, maxMs)
        job = viewModelScope.launch {
            try {
                Codec2.open(codec.mode).use { c2 ->
                    service.audioIo
                        .record(c2.samplesPerFrame * FRAMES_PER_BLOCK)
                        .takeWhile { nowMillis - started < maxMs }
                        .collect { pcm ->
                            val encoded = c2.encode(pcm)
                            lock.withLock { noteFrames.write(encoded) }
                            stateFlow.value = ComposerVoiceState.RecordingNote(nowMillis - started, maxMs)
                        }
                }
                // Reached the length limit: send what was recorded.
                finishNote(contactKey, send = true)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.w { "Voice recording failed: ${e::class.simpleName}" }
                stateFlow.value = ComposerVoiceState.Idle
                noticeFlow.value = VoiceNotice.MIC_FAILED
            }
        }
    }

    fun finishNote(contactKey: String, send: Boolean) {
        if (stateFlow.value !is ComposerVoiceState.RecordingNote) return
        val recording = job
        job = null
        stateFlow.value = ComposerVoiceState.Idle
        viewModelScope.launch {
            recording?.cancel()
            recording?.join()
            val frames: ByteString = lock.withLock { noteFrames.readByteString() }
            if (send && frames.size > 0) {
                noticeFlow.value = service.sendNote(contactKey, noteCodec, frames).toNotice(forward = false)
            }
        }
    }

    // ---- push-to-talk ----

    fun startTalk(contactKey: String) {
        if (stateFlow.value != ComposerVoiceState.Idle || !supported) return
        player.stop()
        val (tx, route) = service.beginLive(contactKey)
        if (tx == null) {
            noticeFlow.value = (route as? LiveRoute.Unavailable)?.reason.toNotice()
            return
        }
        live = tx
        val started = nowMillis
        stateFlow.value = ComposerVoiceState.Talking(route == LiveRoute.Mqtt, 0)
        job = viewModelScope.launch {
            try {
                Codec2.open(tx.codec.mode).use { c2 ->
                    service.audioIo.record(c2.samplesPerFrame * tx.framesPerPacket).collect { pcm ->
                        tx.send(c2.encode(pcm).toByteString())
                        stateFlow.value = ComposerVoiceState.Talking(route == LiveRoute.Mqtt, nowMillis - started)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.w { "Push-to-talk failed: ${e::class.simpleName}" }
                noticeFlow.value = VoiceNotice.MIC_FAILED
                stateFlow.value = ComposerVoiceState.Idle
            }
        }
    }

    fun stopTalk() {
        if (stateFlow.value !is ComposerVoiceState.Talking) return
        val recording = job
        val tx = live
        job = null
        live = null
        stateFlow.value = ComposerVoiceState.Idle
        viewModelScope.launch {
            recording?.cancel()
            recording?.join()
            tx?.end()
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MILLIS = 1_000L

        /** Encode in 200 ms blocks (5 frames of 40 ms) so the elapsed time ticks smoothly. */
        const val FRAMES_PER_BLOCK = 5
    }
}

private fun VoiceSendResult.toNotice(forward: Boolean): VoiceNotice? = when (this) {
    VoiceSendResult.SENT -> if (forward) VoiceNotice.FORWARDED else null
    VoiceSendResult.QUEUED -> VoiceNotice.QUEUED
    VoiceSendResult.REJECTED -> VoiceNotice.REJECTED
    VoiceSendResult.TOO_SLOW_FOR_LORA -> VoiceNotice.TOO_SLOW_FOR_LORA
    VoiceSendResult.NO_RADIO -> VoiceNotice.NO_RADIO
    VoiceSendResult.UNSUPPORTED -> VoiceNotice.UNSUPPORTED
}

private fun LiveUnavailableReason?.toNotice(): VoiceNotice = when (this) {
    LiveUnavailableReason.LORA_TOO_SLOW -> VoiceNotice.LIVE_LORA_TOO_SLOW

    LiveUnavailableReason.LORA_BUSY -> VoiceNotice.LIVE_LORA_BUSY

    LiveUnavailableReason.NO_LINK,
    null,
    -> VoiceNotice.LIVE_NO_LINK
}

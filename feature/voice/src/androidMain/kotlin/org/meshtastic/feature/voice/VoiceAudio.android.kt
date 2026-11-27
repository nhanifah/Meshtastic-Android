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

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.codec2.CODEC2_SAMPLE_RATE_HZ

private const val DRAIN_POLL_MS = 20L
private const val DRAIN_SLACK_MS = 1_500L

private object AndroidVoiceAudio : VoiceAudio {
    override val available = true

    // The caller asks for RECORD_AUDIO first; without it AudioRecord fails to initialise and we throw.
    @SuppressLint("MissingPermission")
    override fun record(blockSamples: Int): Flow<ShortArray> = flow {
        val minBytes =
            AudioRecord.getMinBufferSize(
                CODEC2_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CODEC2_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, blockSamples * 2 * 4),
            )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        val effects =
            listOfNotNull(
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
                if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
            )
        effects.forEach { it.enabled = true }
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val block = ShortArray(blockSamples)
                var filled = 0
                while (filled < blockSamples) {
                    val n = record.read(block, filled, blockSamples - filled)
                    check(n >= 0) { "Microphone read failed ($n)" }
                    filled += n
                }
                emit(block)
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { it.release() }
            record.release()
        }
    }
        .flowOn(Dispatchers.IO)

    override fun openOutput(): VoiceAudioOut {
        val minBytes =
            AudioTrack.getMinBufferSize(
                CODEC2_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(CODEC2_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, CODEC2_SAMPLE_RATE_HZ / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track.play()
        return object : VoiceAudioOut {
            private var written = 0L

            override fun write(pcm: ShortArray) {
                val n = track.write(pcm, 0, pcm.size)
                if (n > 0) written += n
            }

            override suspend fun drain() {
                if (written == 0L) return
                val target = written
                // A track only starts once its buffer has filled to a threshold; push silence after the end so a
                // short note is not left sitting below it.
                val pad = ShortArray(maxOf(track.bufferSizeInFrames, CODEC2_SAMPLE_RATE_HZ / 4))
                track.write(pad, 0, pad.size, AudioTrack.WRITE_NON_BLOCKING)
                val limitMs = target * 1000 / CODEC2_SAMPLE_RATE_HZ + DRAIN_SLACK_MS
                withTimeoutOrNull(limitMs) {
                    while ((track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL) < target) delay(DRAIN_POLL_MS)
                }
            }

            override fun close() {
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}

actual fun platformVoiceAudio(): VoiceAudio = AndroidVoiceAudio

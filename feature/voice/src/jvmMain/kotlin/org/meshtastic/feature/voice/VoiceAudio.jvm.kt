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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.meshtastic.core.codec2.CODEC2_SAMPLE_RATE_HZ
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException

/**
 * Desktop audio through javax.sound. Many sound cards cannot run at 8 kHz, so the line is opened at 48 kHz when needed
 * and resampled by an integer factor (averaging down, repeating up): plenty for a 4 kHz-wide speech codec.
 */
private object JvmVoiceAudio : VoiceAudio {
    private const val FALLBACK_RATE = 48_000
    private const val BYTES_PER_SAMPLE = 2

    override val available = true

    private fun format(rate: Int) = AudioFormat(rate.toFloat(), 16, 1, true, false)

    override fun record(blockSamples: Int): Flow<ShortArray> = flow {
        val (line, rate) =
            listOf(CODEC2_SAMPLE_RATE_HZ, FALLBACK_RATE).firstNotNullOfOrNull { rate ->
                runCatching { AudioSystem.getTargetDataLine(format(rate)).apply { open(format(rate)) } to rate }
                    .getOrNull()
            } ?: throw LineUnavailableException("No microphone")
        val factor = rate / CODEC2_SAMPLE_RATE_HZ
        val raw = ByteArray(blockSamples * factor * BYTES_PER_SAMPLE)
        try {
            line.start()
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < raw.size) {
                    val n = line.read(raw, filled, raw.size - filled)
                    check(n >= 0) { "Microphone read failed" }
                    filled += n
                }
                emit(
                    ShortArray(blockSamples) { i ->
                        var sum = 0
                        for (k in 0 until factor) sum += sampleAt(raw, i * factor + k)
                        (sum / factor).toShort()
                    },
                )
            }
        } finally {
            line.stop()
            line.close()
        }
    }
        .flowOn(Dispatchers.IO)

    override fun openOutput(): VoiceAudioOut {
        val (line, rate) =
            listOf(CODEC2_SAMPLE_RATE_HZ, FALLBACK_RATE).firstNotNullOfOrNull { rate ->
                runCatching { AudioSystem.getSourceDataLine(format(rate)).apply { open(format(rate)) } to rate }
                    .getOrNull()
            } ?: throw LineUnavailableException("No speaker")
        val factor = rate / CODEC2_SAMPLE_RATE_HZ
        line.start()
        return object : VoiceAudioOut {
            override fun write(pcm: ShortArray) {
                val bytes = ByteArray(pcm.size * factor * BYTES_PER_SAMPLE)
                var o = 0
                for (s in pcm) {
                    repeat(factor) {
                        bytes[o++] = (s.toInt() and 0xFF).toByte()
                        bytes[o++] = (s.toInt() shr 8).toByte()
                    }
                }
                line.write(bytes, 0, bytes.size)
            }

            override suspend fun drain() {
                line.drain()
            }

            override fun close() {
                line.stop()
                line.close()
            }
        }
    }

    private fun sampleAt(raw: ByteArray, index: Int): Int =
        (raw[index * 2].toInt() and 0xFF) or (raw[index * 2 + 1].toInt() shl 8)
}

actual fun platformVoiceAudio(): VoiceAudio = JvmVoiceAudio

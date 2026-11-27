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
package org.meshtastic.core.codec2

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

class Codec2RoundTripTest {

    /** A voiced-speech stand-in: 140 Hz fundamental with harmonics, slowly amplitude-modulated. */
    private fun syntheticVoice(seconds: Int): ShortArray {
        val n = CODEC2_SAMPLE_RATE_HZ * seconds
        return ShortArray(n) { i ->
            val t = i.toDouble() / CODEC2_SAMPLE_RATE_HZ
            val envelope = 0.6 + 0.4 * sin(2 * PI * 3 * t)
            val s = (1..8).sumOf { h -> sin(2 * PI * 140 * h * t) / h }
            (envelope * s * 6000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    @Test
    fun nativeLibraryLoadsOnTheHost() {
        assertTrue(Codec2.isAvailable, "libcodec2jni was not bundled for ${desktopPlatformId()}")
    }

    @Test
    fun everyModeRoundTripsWithTheExpectedBitrate() {
        val pcm = syntheticVoice(seconds = 2)
        Codec2Mode.entries.forEach { mode ->
            Codec2.open(mode).use { codec ->
                val frameMs = codec.samplesPerFrame * 1000 / CODEC2_SAMPLE_RATE_HZ
                // Bytes are whole, so the payload rate is at least the nominal bit rate and less than one byte more
                // per frame.
                val payloadBps = codec.bytesPerFrame * 8 * 1000 / frameMs
                assertTrue(payloadBps >= mode.bitsPerSecond && payloadBps < mode.bitsPerSecond + 8 * 1000 / frameMs)

                val whole = pcm.copyOf(pcm.size / codec.samplesPerFrame * codec.samplesPerFrame)
                val encoded = codec.encode(whole)
                assertEquals(whole.size / codec.samplesPerFrame * codec.bytesPerFrame, encoded.size)

                val decoded = codec.decode(encoded)
                assertEquals(whole.size, decoded.size)
                // A vocoder does not preserve the waveform, but it must preserve loudness roughly.
                val ratio = rms(decoded.copyOfRange(decoded.size / 4, decoded.size)) / rms(whole)
                assertTrue(ratio in 0.2..3.0, "$mode output level ratio $ratio")
            }
        }
    }

    @Test
    fun partialFramesAreRejected() {
        Codec2.open(Codec2Mode.MODE_1300).use { codec ->
            assertFailsWith<IllegalArgumentException> { codec.encode(ShortArray(codec.samplesPerFrame + 1)) }
            assertFailsWith<IllegalArgumentException> { codec.decode(ByteArray(codec.bytesPerFrame - 1)) }
        }
    }

    @Test
    fun aClosedCodecRefusesWork() {
        val codec = Codec2.open(Codec2Mode.MODE_700C)
        codec.close()
        codec.close() // idempotent
        assertFailsWith<IllegalStateException> { codec.encode(ShortArray(codec.samplesPerFrame)) }
    }

    /** Prints the encode+decode cost per second of audio; a budget check, not a benchmark. */
    @Test
    fun encodingIsFarFasterThanRealTime() {
        val pcm = syntheticVoice(seconds = 10)
        Codec2Mode.entries.forEach { mode ->
            Codec2.open(mode).use { codec ->
                val whole = pcm.copyOf(pcm.size / codec.samplesPerFrame * codec.samplesPerFrame)
                codec.decode(codec.encode(whole)) // warm-up
                val took = measureTime { codec.decode(codec.encode(whole)) }
                println("codec2 $mode: encode+decode of 10 s audio took $took")
                assertTrue(took.inWholeMilliseconds < 5_000, "$mode took $took for 10 s of audio")
            }
        }
    }
}

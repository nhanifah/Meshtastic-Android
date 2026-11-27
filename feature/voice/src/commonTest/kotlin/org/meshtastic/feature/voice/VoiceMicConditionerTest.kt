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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class VoiceMicConditionerTest {
    private fun tone(hz: Double, amplitude: Int, samples: Int, offset: Int = 0) =
        ShortArray(samples) { i -> (amplitude * sin(2 * PI * hz * (i + offset) / 8000)).toInt().toShort() }

    private fun ShortArray.peak() = maxOf { abs(it.toInt()) }

    /** Runs [seconds] of a signal through in 40 ms blocks, as the recorder does, and returns the last block. */
    private fun settle(c: VoiceMicConditioner, seconds: Int, block: (Int) -> ShortArray): ShortArray {
        var last = ShortArray(0)
        repeat(seconds * 25) { last = c.process(block(it * 320)) }
        return last
    }

    @Test
    fun quietSpeechIsBroughtUpToAboutMinusSixDb() {
        val c = VoiceMicConditioner()
        settle(c, 1) { ShortArray(320) { 20 } } // a little room noise first
        val out = settle(c, 2) { tone(300.0, 800, 320, it) } // about -32 dBFS, like the recording
        assertTrue(out.peak() in 9_000..24_000, "peak ${out.peak()}")
    }

    /** Magnitude of [hz] in [pcm] (a single DFT bin). */
    private fun level(pcm: ShortArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        pcm.forEachIndexed { i, s ->
            re += s * cos(2 * PI * hz * i / 8000)
            im += s * sin(2 * PI * hz * i / 8000)
        }
        return sqrt(re * re + im * im)
    }

    @Test
    fun mainsHumIsCutRelativeToSpeech() {
        val c = VoiceMicConditioner()
        // 50 Hz hum as loud as a 300 Hz voice; 800 samples hold whole cycles of both.
        val input = { offset: Int ->
            ShortArray(800) { i ->
                (4_000 * sin(2 * PI * 50 * (i + offset) / 8000) + 4_000 * sin(2 * PI * 300 * (i + offset) / 8000))
                    .toInt()
                    .toShort()
            }
        }
        var out = ShortArray(0)
        repeat(20) { out = c.process(input(it * 800)) }
        val ratio = level(out, 50.0) / level(out, 300.0)
        assertTrue(ratio < 0.35, "hum/voice ratio $ratio")
    }

    @Test
    fun pausesAreNotBoostedIntoNoise() {
        val c = VoiceMicConditioner()
        settle(c, 2) { tone(300.0, 800, 320, it) }
        val noise = settle(c, 2) { ShortArray(320) { i -> if (i % 2 == 0) 30 else -30 } }
        assertTrue(noise.peak() < 300, "pause noise peak ${noise.peak()}")
    }

    @Test
    fun loudSpeechIsLimitedNotWrapped() {
        val c = VoiceMicConditioner()
        val out = settle(c, 1) { tone(300.0, 32_000, 320, it) }
        assertTrue(out.peak() <= 32_767 && out.peak() > 8_000, "peak ${out.peak()}")
    }

    /** Fan-like room noise that flutters block to block (deterministic, so the test is stable). */
    private fun roomNoise(samples: Int, seed: Int): ShortArray {
        var state = seed * 1_103_515_245 + 12_345
        return ShortArray(samples) { i ->
            state = state * 1_103_515_245 + 12_345
            val flutter = if ((i / 80) % 3 == 0) 2.0 else 1.0
            ((state shr 16) % 300 * flutter).toInt().toShort()
        }
    }

    private fun ShortArray.rms() = sqrt(sumOf { it.toDouble() * it } / size)

    @Test
    fun flutteringRoomNoiseIsNotBoosted() {
        val c = VoiceMicConditioner()
        var inRms = 0.0
        var outRms = 0.0
        repeat(100) { block ->
            val input = roomNoise(320, block)
            val out = c.process(input)
            if (block >= 50) {
                inRms += input.rms()
                outRms += out.rms()
            }
        }
        assertTrue(outRms <= inRms, "room noise went from $inRms to $outRms")
    }

    @Test
    fun aWordAfterSilenceDoesNotBurst() {
        val c = VoiceMicConditioner()
        settle(c, 2) { tone(300.0, 400, 320, it) } // quiet speech winds the gain up
        settle(c, 1) { ShortArray(320) } // a pause
        val onset = c.process(tone(300.0, 20_000, 320)) // then a loud syllable
        assertTrue(onset.peak() <= 26_000, "onset peak ${onset.peak()}")
    }
}

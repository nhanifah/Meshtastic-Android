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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class VoiceNoiseSuppressorTest {
    private fun signal(n: Int) = DoubleArray(n) { i ->
        3_000 * sin(2 * PI * 220 * i / 8000) + 1_500 * sin(2 * PI * 1_330 * i / 8000 + 1)
    }

    @Test
    fun unityGainIsAPureDelay() {
        val ns = VoiceNoiseSuppressor(gainFloor = 1.0)
        val input = signal(8_000)
        // Odd block sizes, as a recorder may deliver them.
        val out = ArrayList<Double>()
        var at = 0
        var size = 37
        while (at < input.size) {
            val end = minOf(at + size, input.size)
            out += ns.process(input.copyOfRange(at, end)).toList()
            at = end
            size = size * 7 % 401 + 1
        }
        val delay = VoiceNoiseSuppressor.FRAME
        var worst = 0.0
        for (i in 1_000 until input.size) worst = maxOf(worst, abs(out[i] - input[i - delay]))
        assertTrue(out.size == input.size)
        assertTrue(worst < 1.0, "reconstruction error $worst")
    }

    @Test
    fun steadyNoiseIsCutAndSpeechKept() {
        val ns = VoiceNoiseSuppressor()
        var seed = 12_345
        fun noise() = DoubleArray(1_600) {
            seed = seed * 1_103_515_245 + 12_345
            ((seed shr 16) % 400).toDouble()
        }
        repeat(10) { ns.process(noise()) } // two seconds of room
        val quiet = ns.process(noise())
        val n = noise()
        val tone = signal(1_600)
        val mixed = ns.process(DoubleArray(1_600) { n[it] + tone[it] })
        fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
        assertTrue(rms(quiet) < rms(n) / 3, "noise ${rms(n)} -> ${rms(quiet)}")
        assertTrue(rms(mixed) > rms(tone) * 0.7, "speech ${rms(tone)} -> ${rms(mixed)}")
    }
}

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

import org.meshtastic.core.codec2.CODEC2_SAMPLE_RATE_HZ
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cleans microphone audio before Codec2 sees it. Codec2 models speech by its pitch; a quiet, hum-laden input makes the
 * pitch tracker lose the voice and the result turns buzzy and robotic. So each block is:
 * 1. high-passed at [HIGH_PASS_HZ] (hum, wind and handling noise carry no speech),
 * 2. levelled by a software AGC that brings speech to about −6 dBFS but does not raise the pauses between words, since
 *    boosting background noise is what Codec2 handles worst,
 * 3. soft-limited so a shout never wraps around.
 *
 * Stateful: use one instance per recording.
 */
class VoiceMicConditioner(private val sampleRate: Int = CODEC2_SAMPLE_RATE_HZ) {
    // Biquad high-pass (RBJ cookbook, Butterworth Q).
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private val subBlock = sampleRate / 100 // 10 ms
    private var envelope = 0.0
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var gain = 1.0
    private var hangover = 0

    init {
        val w0 = 2 * PI * HIGH_PASS_HZ / sampleRate
        val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
        val cosW = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + cosW) / 2 / a0
        b1 = -(1 + cosW) / a0
        b2 = (1 + cosW) / 2 / a0
        a1 = -2 * cosW / a0
        a2 = (1 - alpha) / a0
    }

    fun process(pcm: ShortArray): ShortArray {
        val filtered = DoubleArray(pcm.size) { i -> highPass(pcm[i].toDouble()) }
        val out = ShortArray(pcm.size)
        var start = 0
        while (start < filtered.size) {
            val end = minOf(start + subBlock, filtered.size)
            levelBlock(filtered, start, end, out)
            start = end
        }
        return out
    }

    private fun highPass(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    private fun levelBlock(samples: DoubleArray, start: Int, end: Int, out: ShortArray) {
        var peak = 0.0
        var energy = 0.0
        for (i in start until end) {
            val a = kotlin.math.abs(samples[i])
            if (a > peak) peak = a
            energy += samples[i] * samples[i]
        }
        val rms = sqrt(energy / (end - start))

        // The noise floor follows quiet stretches down at once and creeps up slowly, so speech never drags it up.
        noiseFloor = if (rms < noiseFloor) rms.coerceAtLeast(MIN_NOISE_FLOOR) else noiseFloor * NOISE_RISE
        val voiced = rms > noiseFloor * SPEECH_OVER_NOISE && rms > MIN_SPEECH_RMS
        // Hold "talking" through the short gaps inside and between words, so soft consonants keep their gain.
        hangover = if (voiced) HANGOVER_BLOCKS else (hangover - 1).coerceAtLeast(0)
        val talking = hangover > 0

        // Peak envelope: instant attack, ~0.5 s release, only tracked on voiced blocks.
        if (voiced) envelope = if (peak > envelope) peak else envelope * ENVELOPE_RELEASE

        val wanted = if (talking && envelope > 0) (TARGET_PEAK / envelope).coerceIn(MIN_GAIN, MAX_GAIN) else MIN_GAIN
        // Come down fast for a loud peak (no clipping), ease down in a pause, go up gently (no noise burst at the
        // start of a word).
        val step =
            when {
                wanted >= gain -> GAIN_RISE
                talking -> GAIN_FALL
                else -> GAIN_RELEASE
            }
        val from = gain
        val to = gain + (wanted - gain) * step
        gain = to

        val n = end - start
        for (i in 0 until n) {
            val g = from + (to - from) * (i + 1) / n
            out[start + i] = softLimit(samples[start + i] * g)
        }
    }

    private fun softLimit(x: Double): Short {
        val a = kotlin.math.abs(x)
        val y =
            if (a <= KNEE) {
                a
            } else {
                // Compress everything above the knee into the remaining headroom.
                val over = (a - KNEE) / (FULL_SCALE - KNEE)
                KNEE + (FULL_SCALE - KNEE) * (over / (1 + over))
            }
        return (if (x < 0) -y else y).toInt().coerceIn(-32_768, 32_767).toShort()
    }

    companion object {
        const val HIGH_PASS_HZ = 100.0
        private const val BUTTERWORTH_Q = 0.7071

        /** About −6 dBFS. */
        const val TARGET_PEAK = 16_000.0
        private const val MIN_GAIN = 1.0

        /** +24 dB: enough for a phone held at arm's length, not enough to turn room noise into a roar. */
        const val MAX_GAIN = 16.0
        private const val GAIN_RISE = 0.08
        private const val GAIN_FALL = 0.5
        private const val GAIN_RELEASE = 0.1
        private const val HANGOVER_BLOCKS = 25 // 250 ms
        private const val ENVELOPE_RELEASE = 0.985

        private const val INITIAL_NOISE_FLOOR = 200.0
        private const val MIN_NOISE_FLOOR = 4.0
        private const val NOISE_RISE = 1.003
        private const val SPEECH_OVER_NOISE = 2.5
        private const val MIN_SPEECH_RMS = 40.0

        private const val KNEE = 24_000.0
        private const val FULL_SCALE = 32_767.0
    }
}

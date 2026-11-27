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
 * 2. levelled by a software AGC that brings speech to about −6 dBFS, but no further than keeps the room noise under
 *    about −47 dBFS, and that pulls the pauses between words down (background noise is what Codec2 handles worst),
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
    private var smoothedRms = 0.0

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

        // Judge speech on a smoothed level: a raw 10 ms minimum sits far below the room's real noise, which made
        // every fan or traffic flutter count as speech and get boosted.
        smoothedRms += (rms - smoothedRms) * RMS_SMOOTHING
        val nextFloor =
            if (smoothedRms < noiseFloor) {
                noiseFloor + (smoothedRms - noiseFloor) * NOISE_FALL
            } else {
                noiseFloor * NOISE_RISE
            }
        noiseFloor = nextFloor.coerceAtLeast(MIN_NOISE_FLOOR)
        val voiced = smoothedRms > noiseFloor * SPEECH_OVER_NOISE && rms > MIN_SPEECH_RMS
        // Hold "talking" through the short gaps inside and between words, so soft consonants keep their gain.
        hangover = if (voiced) HANGOVER_BLOCKS else (hangover - 1).coerceAtLeast(0)
        val talking = hangover > 0

        // Speech peak envelope: instant attack, slow (~2 s) release, so a pause does not wind the gain back up.
        if (voiced) envelope = if (peak > envelope) peak else envelope * ENVELOPE_RELEASE

        // Never boost so far that the room noise itself comes out louder than about -47 dBFS.
        val snrCap = (NOISE_CEILING / noiseFloor).coerceIn(MIN_GAIN, MAX_GAIN)
        val wanted = if (talking && envelope > 0) (TARGET_PEAK / envelope).coerceIn(MIN_GAIN, snrCap) else GATE_GAIN
        val from = gain
        var to =
            when {
                wanted >= gain -> gain + (wanted - gain) * GAIN_RISE

                // A loud onset: drop at once, in this very block.
                talking -> wanted

                else -> gain + (wanted - gain) * GAIN_RELEASE
            }
        // No block may be pushed past the limiter's knee: this is what made the first syllable after a pause burst.
        if (peak > 0) to = minOf(to, KNEE / peak)
        gain = to

        val n = end - start
        val ramp = from < to // ramp up smoothly; when coming down, apply the lower gain to the whole block
        for (i in 0 until n) {
            val g = if (ramp) from + (to - from) * (i + 1) / n else to
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

        /** Pauses are pulled down 9 dB: Codec2 turns residual room noise into a buzzy warble. */
        const val GATE_GAIN = 0.35

        /** About -47 dBFS: the loudest the room noise may come out. */
        private const val NOISE_CEILING = 150.0
        private const val GAIN_RISE = 0.25
        private const val GAIN_RELEASE = 0.1
        private const val HANGOVER_BLOCKS = 20 // 200 ms
        private const val ENVELOPE_RELEASE = 0.997

        private const val RMS_SMOOTHING = 0.3
        private const val INITIAL_NOISE_FLOOR = 200.0
        private const val MIN_NOISE_FLOOR = 4.0
        private const val NOISE_FALL = 0.3
        private const val NOISE_RISE = 1.002
        private const val SPEECH_OVER_NOISE = 4.0
        private const val MIN_SPEECH_RMS = 40.0

        private const val KNEE = 24_000.0
        private const val FULL_SCALE = 32_767.0
    }
}

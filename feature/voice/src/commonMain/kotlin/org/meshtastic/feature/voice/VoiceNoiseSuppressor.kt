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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming noise suppressor for 8 kHz speech, run before Codec2. Codec2 models every frame as harmonics of a pitch;
 * background noise makes it call noisy frames voiced and paint buzzing harmonics across the band, which is the "robot"
 * sound. Low-bit-rate voice projects (FreeDV among them) clean the input first for the same reason.
 *
 * Short-time Fourier analysis (32 ms frames, 50 % overlap, square-root Hann windows so the overlap-add is exact), a
 * per-bin noise estimate that follows the quiet troughs and rises slowly through speech, and a decision-directed Wiener
 * gain (Ephraim–Malah) with a floor, so residual noise stays smooth instead of chirping ("musical noise").
 *
 * Latency is one frame (32 ms). [process] returns exactly as many samples as it is given.
 */
class VoiceNoiseSuppressor(
    private val gainFloor: Double = GAIN_FLOOR,
    private val noiseBias: Double = NOISE_BIAS,
    private val ddAlpha: Double = DD_ALPHA,
) {
    private val window = DoubleArray(FRAME) { i -> sqrt(0.5 - 0.5 * cos(2 * PI * i / FRAME)) }
    private val history = DoubleArray(FRAME)
    private var historyFill = 0 // new samples since the last frame
    private val overlap = DoubleArray(FRAME)
    private val pending = ArrayDeque<Double>()

    private val smoothed = DoubleArray(BINS)
    private val noise = DoubleArray(BINS)
    private val lastCleanPower = DoubleArray(BINS)
    private var frames = 0

    private val re = DoubleArray(FRAME)
    private val im = DoubleArray(FRAME)

    init {
        repeat(HOP) { pending.addLast(0.0) }
    }

    fun process(input: DoubleArray): DoubleArray {
        for (x in input) {
            // Slide the analysis history by one sample at a time; a frame runs every HOP samples.
            history.copyInto(history, 0, 1, FRAME)
            history[FRAME - 1] = x
            if (++historyFill == HOP) {
                historyFill = 0
                runFrame()
            }
        }
        return DoubleArray(input.size) { pending.removeFirst() }
    }

    private fun runFrame() {
        for (i in 0 until FRAME) {
            re[i] = history[i] * window[i]
            im[i] = 0.0
        }
        fft(re, im, inverse = false)
        frames++

        for (k in 0 until BINS) {
            val power = re[k] * re[k] + im[k] * im[k]
            smoothed[k] = if (frames == 1) power else smoothed[k] * PSD_SMOOTHING + power * (1 - PSD_SMOOTHING)
            noise[k] =
                when {
                    // Learn the room during the first quarter second (the user has only just pressed the button).
                    frames <= LEARN_FRAMES -> noise[k] + (smoothed[k] - noise[k]) / frames

                    smoothed[k] < noise[k] -> smoothed[k]

                    else -> noise[k] * NOISE_RISE
                }.coerceAtLeast(MIN_POWER)

            val lambda = noise[k] * noiseBias
            val posteriori = power / lambda
            val priori = ddAlpha * lastCleanPower[k] / lambda + (1 - ddAlpha) * max(posteriori - 1, 0.0)
            val gain = max(priori / (1 + priori), gainFloor)
            lastCleanPower[k] = gain * gain * power

            re[k] *= gain
            im[k] *= gain
            if (k in 1 until FRAME / 2) {
                re[FRAME - k] = re[k]
                im[FRAME - k] = -im[k]
            }
        }
        fft(re, im, inverse = true)

        for (i in 0 until FRAME) overlap[i] += re[i] * window[i]
        for (i in 0 until HOP) pending.addLast(overlap[i])
        overlap.copyInto(overlap, 0, HOP, FRAME)
        overlap.fill(0.0, FRAME - HOP, FRAME)
    }

    companion object {
        const val FRAME = 256
        const val HOP = FRAME / 2
        private const val BINS = FRAME / 2 + 1

        private const val PSD_SMOOTHING = 0.7
        private const val LEARN_FRAMES = 15

        /** ~+1.7 dB/s while speech covers the troughs: slow enough not to learn the voice as noise. */
        private const val NOISE_RISE = 1.003

        /** The trough of a smoothed periodogram sits under the mean noise power; this lifts it back. */
        const val NOISE_BIAS = 1.8

        /** Below the textbook 0.98: word onsets come through faster, for slightly less smoothing. */
        const val DD_ALPHA = 0.9

        /**
         * −14 dB: deep enough to clear the room, shallow enough that residual noise stays smooth and the voice keeps
         * its colour (picked on the offline bench in VoiceSimulationTest).
         */
        const val GAIN_FLOOR = 0.2
        private const val MIN_POWER = 1e-3

        /** In-place radix-2 FFT; the inverse is scaled by 1/n. */
        internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]
                    re[i] = re[j]
                    re[j] = t
                    t = im[i]
                    im[i] = im[j]
                    im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = (if (inverse) 2 else -2) * PI / len
                val wr = cos(angle)
                val wi = sin(angle)
                for (start in 0 until n step len) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = start + k
                        val b = a + len / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                        val next = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = next
                    }
                }
                len = len shl 1
            }
            if (inverse) {
                for (i in 0 until n) {
                    re[i] /= n
                    im[i] /= n
                }
            }
        }
    }
}

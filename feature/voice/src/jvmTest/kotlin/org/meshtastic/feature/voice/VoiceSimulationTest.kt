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

import org.meshtastic.core.codec2.Codec2
import org.meshtastic.core.codec2.Codec2Mode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Offline listening bench for the voice chain, run only when `build/voice-sim/in` holds 8 kHz mono 16-bit WAVs:
 * `cleanN.wav` references and noisy takes named `<condition>N.wav`. Each take goes through the app's chain (mic
 * conditioner → Codec2 → playback levelling) in three variants (raw, AGC only, noise suppression + AGC) and two modes,
 * is written to `build/voice-sim/out`, and is scored against the clean reference through Codec2 alone.
 */
class VoiceSimulationTest {
    private val root = File("build/voice-sim")

    /** Chains to compare; extend this list while tuning. */
    private val variants: List<Pair<String, (() -> VoiceMicConditioner)?>> =
        listOf(
            "raw" to null,
            "hp" to { VoiceMicConditioner(suppressor = null, level = false) },
            "agc" to { VoiceMicConditioner(suppressor = null) },
            "ns" to { VoiceMicConditioner(level = false) },
            "app" to { VoiceMicConditioner() },
        )

    @Test
    fun simulate() {
        val inDir = File(root, "in")
        if (!inDir.isDirectory || !Codec2.isAvailable) return
        val outDir = File(root, "out").apply { mkdirs() }
        val report = StringBuilder("take mode variant | noiseLead pauseNoise speech snr | lsd\n")
        val takes = inDir.listFiles { f -> f.extension == "wav" && !f.name.startsWith("clean") }!!.sortedBy { it.name }
        for (mode in listOf(Codec2Mode.MODE_3200, Codec2Mode.MODE_1300)) {
            for (take in takes) {
                val index = take.nameWithoutExtension.takeLast(1)
                val clean = readWav(File(inDir, "clean$index.wav"))
                val reference = codec(clean, mode)
                writeWav(File(outDir, "clean$index-${mode.bitsPerSecond}.wav"), reference)
                val noisy = readWav(take)
                for ((variant, make) in variants) {
                    val conditioned = make?.let { condition(noisy, it()) } ?: noisy
                    val decoded = codec(conditioned, mode).let { VoiceLevel.apply(it, VoiceLevel.gainFor(listOf(it))) }
                    writeWav(File(outDir, "${take.nameWithoutExtension}-${mode.bitsPerSecond}-$variant.wav"), decoded)
                    report.append(
                        "${take.nameWithoutExtension} ${mode.bitsPerSecond} $variant | ${score(
                            clean,
                            clean,
                            decoded,
                        )}\n",
                    )
                }
            }
        }
        File(root, "report.txt").writeText(report.toString())
        println(report)
    }

    private companion object {
        const val BANDS = 20
        val BAND_EDGES =
            IntArray(BANDS + 1) { i ->
                val hz = 150.0 * Math.pow(3800.0 / 150.0, i.toDouble() / BANDS)
                (hz / 8_000 * 256).toInt().coerceAtLeast(i + 4)
            }
    }

    /** Feeds the recorder's 200 ms blocks, as the app does. */
    private fun condition(pcm: ShortArray, c: VoiceMicConditioner): ShortArray {
        val out = ShortArray(pcm.size)
        var at = 0
        while (at < pcm.size) {
            val end = minOf(at + 1_600, pcm.size)
            c.process(pcm.copyOfRange(at, end)).copyInto(out, at)
            at = end
        }
        return out
    }

    private fun codec(pcm: ShortArray, mode: Codec2Mode): ShortArray = Codec2.open(mode).use { c2 ->
        val usable = pcm.size / c2.samplesPerFrame * c2.samplesPerFrame
        c2.decode(c2.encode(pcm.copyOf(usable)))
    }

    private fun db(x: Double) = 20 * log10(x.coerceAtLeast(1e-9) / 32_768)

    private fun rms(p: ShortArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += p[i].toDouble() * p[i]
        return sqrt(e / (to - from).coerceAtLeast(1))
    }

    private fun score(clean: ShortArray, reference: ShortArray, out: ShortArray): String {
        val frame = 160 // 20 ms
        val n = minOf(clean.size, out.size, reference.size) / frame
        val lag = bestLag(reference, out)
        val speechFrames = (0 until n).filter { db(rms(clean, it * frame, (it + 1) * frame)) > -35 }
        val pauseFrames =
            (0 until n).filter { it * frame > 8_000 && db(rms(clean, it * frame, (it + 1) * frame)) < -60 }
        fun meanDb(frames: List<Int>, p: ShortArray, shift: Int) = db(
            sqrt(
                frames
                    .map { f ->
                        val a = (f * frame + shift).coerceIn(0, p.size - frame)
                        rms(p, a, a + frame).let { it * it }
                    }
                    .average(),
            ),
        )
        val lead = db(rms(out, 2_800, 6_000))
        val pause = if (pauseFrames.isEmpty()) Double.NaN else meanDb(pauseFrames, out, lag)
        val speech = meanDb(speechFrames, out, lag)
        val lsd = lsd(reference, out, lag, speechFrames.map { it * frame })
        return "%6.1f %6.1f %6.1f %5.1f | %5.2f".format(lead, pause, speech, speech - maxOf(lead, pause), lsd)
    }

    /**
     * Output delay against the reference, from their 1 ms energy envelopes over ±75 ms. Codec2 does not keep waveform
     * phase, so a waveform cross-correlation between two decodes lands on a wrong lag.
     */
    private fun bestLag(a: ShortArray, b: ShortArray): Int {
        val step = 8
        fun envelope(p: ShortArray) = DoubleArray(p.size / step) { i -> log10(1 + rms(p, i * step, (i + 1) * step)) }
        val ea = envelope(a)
        val eb = envelope(b)
        val ma = ea.average()
        val mb = eb.average()
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val n = minOf(ea.size, eb.size)
        for (lag in -75..75) {
            var s = 0.0
            for (i in 100 until n - 100) s += (ea[i] - ma) * (eb[i + lag] - mb)
            if (s > bestScore) {
                bestScore = s
                best = lag
            }
        }
        return best * step
    }

    /** Mean log-spectral distance (dB) between reference and output on speech frames, levels matched. */
    private fun lsd(reference: ShortArray, out: ShortArray, lag: Int, starts: List<Int>): Double {
        val size = 256
        val gain = rms(reference, 0, reference.size) / rms(out, 0, out.size).coerceAtLeast(1e-9)
        val dists = starts.mapNotNull { s ->
            val o = s + lag
            if (s + size > reference.size || o < 0 || o + size > out.size) return@mapNotNull null
            val pa = spectrum(DoubleArray(size) { reference[s + it].toDouble() })
            val pb = spectrum(DoubleArray(size) { out[o + it] * gain })
            // 20 bands, log-spaced 150-3800 Hz: the spectral envelope, not each harmonic and valley.
            var d = 0.0
            for (band in 0 until BANDS) {
                var ea = 1e3
                var eb = 1e3
                for (k in BAND_EDGES[band] until BAND_EDGES[band + 1]) {
                    ea += pa[k]
                    eb += pb[k]
                }
                val diff = 10 * log10(ea / eb)
                d += diff * diff
            }
            sqrt(d / BANDS)
        }
        return dists.average()
    }

    private fun spectrum(x: DoubleArray): DoubleArray {
        val re = DoubleArray(x.size) { x[it] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / x.size)) }
        val im = DoubleArray(x.size)
        VoiceNoiseSuppressor.fft(re, im, inverse = false)
        return DoubleArray(x.size / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun readWav(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val len = b.getInt(pos + 4)
            if (id == "data") {
                return ShortArray(len / 2) { b.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk in ${f.name}")
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
        b.putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        f.writeBytes(b.array())
    }
}

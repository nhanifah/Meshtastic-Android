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

/** Sample rate of every Codec2 mode: 8 kHz mono, signed 16-bit PCM. */
const val CODEC2_SAMPLE_RATE_HZ: Int = 8000

/**
 * The Codec2 speech modes this app offers. [nativeId] is upstream's `CODEC2_MODE_*` value and never goes on the wire;
 * the voice protocol carries its own stable id so a future library upgrade cannot change what a byte means.
 */
enum class Codec2Mode(val nativeId: Int, val bitsPerSecond: Int) {
    MODE_3200(nativeId = 0, bitsPerSecond = 3200),
    MODE_2400(nativeId = 1, bitsPerSecond = 2400),
    MODE_1600(nativeId = 2, bitsPerSecond = 1600),
    MODE_1400(nativeId = 3, bitsPerSecond = 1400),
    MODE_1300(nativeId = 4, bitsPerSecond = 1300),
    MODE_1200(nativeId = 5, bitsPerSecond = 1200),
    MODE_700C(nativeId = 8, bitsPerSecond = 700),
    ;

    companion object {
        val DEFAULT: Codec2Mode = MODE_1300
    }
}

/**
 * One Codec2 encoder/decoder state for a single [mode]. Not thread-safe: Codec2 keeps history between frames, so use
 * one instance per stream and close it when the stream ends.
 */
interface Codec2Codec : AutoCloseable {
    val mode: Codec2Mode

    /** PCM samples in one frame (160 = 20 ms or 320 = 40 ms at 8 kHz). */
    val samplesPerFrame: Int

    /** Encoded bytes per frame; the last byte may be partly padding. */
    val bytesPerFrame: Int

    /** Encodes whole frames: [pcm] size must be a multiple of [samplesPerFrame]. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes whole frames: [frames] size must be a multiple of [bytesPerFrame]. */
    fun decode(frames: ByteArray): ShortArray
}

/** Entry point. [isAvailable] is false where the native library is missing (iOS for now, or a failed load). */
object Codec2 {
    val isAvailable: Boolean
        get() = codec2NativeAvailable()

    /** Opens a codec for [mode]; throws [IllegalStateException] when [isAvailable] is false. */
    fun open(mode: Codec2Mode = Codec2Mode.DEFAULT): Codec2Codec {
        check(isAvailable) { "Codec2 native library is not available on this platform" }
        return openCodec2(mode)
    }
}

internal expect fun codec2NativeAvailable(): Boolean

internal expect fun openCodec2(mode: Codec2Mode): Codec2Codec

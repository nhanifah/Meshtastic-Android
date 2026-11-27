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

import co.touchlab.kermit.Logger

/** JNI surface of `libcodec2jni` (core/codec2/native/jni/codec2_jni.c). Names must match the C symbols. */
internal object Codec2Native {
    external fun create(mode: Int): Long

    external fun destroy(handle: Long)

    external fun samplesPerFrame(handle: Long): Int

    external fun bytesPerFrame(handle: Long): Int

    external fun encodeFrames(handle: Long, pcm: ShortArray, out: ByteArray, frames: Int)

    external fun decodeFrames(handle: Long, bits: ByteArray, out: ShortArray, frames: Int)

    /** Loaded once; a failure is remembered so callers see [Codec2.isAvailable] = false instead of a crash. */
    val loaded: Boolean by lazy {
        runCatching { loadCodec2Library() }
            .onFailure { Logger.w(it) { "Codec2 native library failed to load" } }
            .isSuccess
    }
}

/** Loads `libcodec2jni` the platform's way, or throws. */
internal expect fun loadCodec2Library()

internal actual fun codec2NativeAvailable(): Boolean = Codec2Native.loaded

internal actual fun openCodec2(mode: Codec2Mode): Codec2Codec = JniCodec2(mode)

private class JniCodec2(override val mode: Codec2Mode) : Codec2Codec {
    private var handle: Long = Codec2Native.create(mode.nativeId)

    init {
        check(handle != 0L) { "codec2_create failed for $mode" }
    }

    override val samplesPerFrame: Int = Codec2Native.samplesPerFrame(handle)
    override val bytesPerFrame: Int = Codec2Native.bytesPerFrame(handle)

    @Synchronized
    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size % samplesPerFrame == 0) { "PCM size ${pcm.size} is not whole $samplesPerFrame-sample frames" }
        val frames = pcm.size / samplesPerFrame
        val out = ByteArray(frames * bytesPerFrame)
        if (frames > 0) Codec2Native.encodeFrames(liveHandle(), pcm, out, frames)
        return out
    }

    @Synchronized
    override fun decode(frames: ByteArray): ShortArray {
        require(frames.size % bytesPerFrame == 0) { "Data size ${frames.size} is not whole $bytesPerFrame-byte frames" }
        val count = frames.size / bytesPerFrame
        val out = ShortArray(count * samplesPerFrame)
        if (count > 0) Codec2Native.decodeFrames(liveHandle(), frames, out, count)
        return out
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            Codec2Native.destroy(handle)
            handle = 0L
        }
    }

    private fun liveHandle(): Long {
        check(handle != 0L) { "Codec2 codec is closed" }
        return handle
    }
}

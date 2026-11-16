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
package org.meshtastic.core.network.radio

/**
 * Wire format between the phone app and the Wear OS app over the Wear Data Layer. Both apps must carry the same
 * application id and signing key, or the Data Layer does not connect them.
 */
object PhoneRelayProtocol {
    /** Advertised by the phone app (res/values/wear.xml) so the watch can find it. */
    const val PHONE_CAPABILITY = "meshtastic_phone_bridge"

    /** Watch → phone, every [HELLO_INTERVAL_MILLIS] while it relays. Empty payload. */
    const val PATH_HELLO = "/meshtastic/hello"

    /** Watch → phone, when it stops relaying. Empty payload. */
    const val PATH_BYE = "/meshtastic/bye"

    /** Watch → phone: one `ToRadio` frame. */
    const val PATH_TO_RADIO = "/meshtastic/toradio"

    /** Phone → watch: one byte, 1 while the phone has a live radio session, else 0. */
    const val PATH_STATUS = "/meshtastic/status"

    /** Phone → watch: one live `FromRadio` frame. */
    const val PATH_FROM_RADIO = "/meshtastic/fromradio"

    /** Phone → watch: a config dump, [encodeBatch]ed so the handshake arrives whole and in order. */
    const val PATH_DUMP = "/meshtastic/dump"

    const val HELLO_INTERVAL_MILLIS = 30_000L

    /** The phone stops relaying to a watch it has not heard from for this long. */
    const val WATCH_TIMEOUT_MILLIS = 95_000L

    /** Each frame as a 4-byte big-endian length followed by its bytes. */
    fun encodeBatch(frames: List<ByteArray>): ByteArray {
        val out = ByteArray(frames.sumOf { it.size + LENGTH_BYTES })
        var at = 0
        frames.forEach { frame ->
            for (i in 0 until LENGTH_BYTES) {
                out[at + i] = (frame.size ushr (BITS_PER_BYTE * (LENGTH_BYTES - 1 - i))).toByte()
            }
            at += LENGTH_BYTES
            frame.copyInto(out, at)
            at += frame.size
        }
        return out
    }

    /** Inverse of [encodeBatch]. A truncated tail is dropped. */
    fun decodeBatch(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (at + LENGTH_BYTES <= bytes.size) {
            var size = 0
            for (i in 0 until LENGTH_BYTES) size = (size shl BITS_PER_BYTE) or (bytes[at + i].toInt() and BYTE_MASK)
            at += LENGTH_BYTES
            if (size < 0 || at + size > bytes.size) break
            frames += bytes.copyOfRange(at, at + size)
            at += size
        }
        return frames
    }

    private const val LENGTH_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
}

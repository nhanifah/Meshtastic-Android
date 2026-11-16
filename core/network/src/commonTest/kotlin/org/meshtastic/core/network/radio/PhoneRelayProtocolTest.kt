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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneRelayProtocolTest {
    @Test
    fun batchRoundTripsFramesInOrder() {
        val frames = listOf(byteArrayOf(1, 2, 3), ByteArray(0), ByteArray(300) { it.toByte() })
        val decoded = PhoneRelayProtocol.decodeBatch(PhoneRelayProtocol.encodeBatch(frames))
        assertEquals(frames.size, decoded.size)
        frames.zip(decoded).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun truncatedTailIsDropped() {
        val bytes = PhoneRelayProtocol.encodeBatch(listOf(byteArrayOf(9), byteArrayOf(7, 7, 7)))
        val decoded = PhoneRelayProtocol.decodeBatch(bytes.copyOf(bytes.size - 1))
        assertEquals(1, decoded.size)
        assertContentEquals(byteArrayOf(9), decoded.single())
    }

    @Test
    fun emptyBatchDecodesToNothing() {
        assertTrue(PhoneRelayProtocol.decodeBatch(ByteArray(0)).isEmpty())
    }
}

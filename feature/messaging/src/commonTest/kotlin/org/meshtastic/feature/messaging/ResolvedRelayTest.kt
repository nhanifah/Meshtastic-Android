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
package org.meshtastic.feature.messaging

import org.meshtastic.core.model.Node
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvedRelayTest {
    private fun node(num: Int, name: String, hopsAway: Int = 0) = Node(
        num = num,
        user = User.Builder().also { it.long_name = name }.build(),
        lastHeard = 1,
        hopsAway = hopsAway,
    )

    @Test
    fun `a single node ending in the byte is named with certainty`() {
        val relay = resolveRelay(0x7f, listOf(node(0x1234567f, "Hilltop"), node(0x12345600, "Other")), ourNodeNum = 1)

        assertEquals("Hilltop", relay.name)
        assertEquals("7f", relay.suffix)
        assertFalse(relay.uncertain)
    }

    @Test
    fun `several nodes ending in the byte pick the closest and say so`() {
        val nodes = listOf(node(0x0000017f, "Far", hopsAway = 3), node(0x0000027f, "Near", hopsAway = 1))

        val relay = resolveRelay(0x7f, nodes, ourNodeNum = 1)

        assertEquals("Near", relay.name)
        assertTrue(relay.uncertain)
    }

    @Test
    fun `an unknown byte falls back to its hex suffix`() {
        val relay = resolveRelay(0x0a, listOf(node(0x1234567f, "Hilltop")), ourNodeNum = 1)

        assertNull(relay.name)
        assertEquals("0a", relay.suffix)
    }

    @Test
    fun `our own node is never named as the relayer`() {
        val relay = resolveRelay(0x7f, listOf(node(0x1234567f, "Me")), ourNodeNum = 0x1234567f)

        assertNull(relay.name)
    }
}

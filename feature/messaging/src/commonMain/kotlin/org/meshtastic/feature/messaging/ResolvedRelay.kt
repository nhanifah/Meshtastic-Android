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

import org.meshtastic.core.common.util.formatString
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName

private const val RELAY_BYTE_MASK = 0xFF

/**
 * The node a relay id byte most likely names.
 *
 * @property name the node's display name, or null when no known node ends in that byte.
 * @property suffix the byte as two hex digits, shown when [name] is null.
 * @property uncertain true when several known nodes end in that byte and the closest one was picked.
 */
data class ResolvedRelay(val name: String?, val suffix: String, val uncertain: Boolean)

/** Two lowercase hex digits for a relay id byte, matching the tail of a `!xxxxxxxx` node id. */
fun relaySuffix(relayByte: Int): String = formatString("%02x", relayByte and RELAY_BYTE_MASK)

/**
 * Resolves [relayByte] against [nodes] the same way [Node.getRelayNode] does, and also reports whether the pick was a
 * guess among several nodes sharing the byte.
 */
fun resolveRelay(relayByte: Int, nodes: List<Node>, ourNodeNum: Int?): ResolvedRelay {
    val byte = relayByte and RELAY_BYTE_MASK
    val candidates = nodes.count { it.num != ourNodeNum && it.lastHeard != 0 && (it.num and RELAY_BYTE_MASK) == byte }
    val node = Node.getRelayNode(byte, nodes, ourNodeNum)
    return ResolvedRelay(
        name = node?.effectiveLongName?.takeIf { it.isNotBlank() },
        suffix = relaySuffix(byte),
        uncertain = candidates > 1,
    )
}

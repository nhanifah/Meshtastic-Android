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
package org.meshtastic.feature.node.scanner

import org.meshtastic.core.model.Node
import org.meshtastic.proto.MeshPacket

/** How a node heard over RF reached our radio. */
enum class RfLink {
    /** Heard straight from the node's own transmission: zero hops, so the SNR/RSSI on file are its own. */
    DIRECT,

    /** Heard over RF, but through one or more relays. The signal on file belongs to the last relay, not the node. */
    RELAYED,

    /** Heard over RF, but the packet did not say how many hops it took (old firmware, or hop fields unset). */
    UNKNOWN_HOPS,
}

/** How far back the scanner looks for a node's last RF packet. */
@Suppress("MagicNumber")
enum class RfScanWindow(val seconds: Long) {
    MINUTES_15(15 * 60L),
    HOUR_1(60 * 60L),
    HOURS_2(2 * 60 * 60L),
    HOURS_6(6 * 60 * 60L),
    HOURS_24(24 * 60 * 60L),
}

enum class RfScanSort {
    /** Strongest direct signal first, then relayed nodes by fewest hops. */
    SIGNAL,

    /** Most recently heard first. */
    LAST_HEARD,
}

data class RfScanEntry(val node: Node, val link: RfLink)

data class RfScanResult(
    val entries: List<RfScanEntry> = emptyList(),
    /** Nodes active inside the window that only reached us over MQTT or the network, so were left out. */
    val excludedNetworkCount: Int = 0,
) {
    val directCount: Int = entries.count { it.link == RfLink.DIRECT }
    val relayedCount: Int = entries.count { it.link == RfLink.RELAYED }
    val unknownHopsCount: Int = entries.count { it.link == RfLink.UNKNOWN_HOPS }
    val total: Int
        get() = entries.size
}

/**
 * Decides which nodes in the node database were really heard over our radio (RF), rather than only arriving over MQTT
 * or a network bridge.
 *
 * A node counts when all of these hold:
 * - it is not our own node and is not ignored;
 * - its last packet was not marked `via_mqtt`, and did not arrive over MQTT or UDP multicast;
 * - it was heard inside the chosen [RfScanWindow];
 * - when [trustHeardFlag] is set, the radio has not said it never heard the node on the current LoRa settings.
 *
 * Counted nodes are split by hops: 0 is [RfLink.DIRECT], more is [RfLink.RELAYED], unknown is [RfLink.UNKNOWN_HOPS].
 */
object RfScanClassifier {
    private val NETWORK_TRANSPORTS =
        setOf(
            MeshPacket.TransportMechanism.TRANSPORT_MQTT.value,
            MeshPacket.TransportMechanism.TRANSPORT_MULTICAST_UDP.value,
        )

    /** True when this node's last packet came over the internet or LAN rather than over the air. */
    fun isNetworkOnly(node: Node): Boolean = node.viaMqtt || node.lastTransport in NETWORK_TRANSPORTS

    /** True when [node] was last heard no longer than [window] before [nowSeconds]. A future time counts as now. */
    fun isInWindow(node: Node, nowSeconds: Long, window: RfScanWindow): Boolean =
        node.lastHeard > 0 && nowSeconds - node.lastHeard <= window.seconds

    fun linkOf(node: Node): RfLink = when {
        node.hopsAway == 0 -> RfLink.DIRECT
        node.hopsAway > 0 -> RfLink.RELAYED
        else -> RfLink.UNKNOWN_HOPS
    }

    /** Returns how [node] was heard over RF, or null when it does not count as an RF node in this window. */
    fun classify(node: Node, nowSeconds: Long, window: RfScanWindow, trustHeardFlag: Boolean): RfLink? = when {
        node.isIgnored -> null
        !isInWindow(node, nowSeconds, window) -> null
        isNetworkOnly(node) -> null
        trustHeardFlag && !node.heardOnCurrentLora -> null
        else -> linkOf(node)
    }

    @Suppress("LongParameterList")
    fun scan(
        nodes: Collection<Node>,
        ourNodeNum: Int?,
        nowSeconds: Long,
        window: RfScanWindow,
        sort: RfScanSort,
        trustHeardFlag: Boolean,
    ): RfScanResult {
        // Deduped by num: the screen keys its list on it.
        val others = nodes.filter { it.num != ourNodeNum && !it.isIgnored }.distinctBy { it.num }
        val entries = others.mapNotNull { node ->
            classify(node, nowSeconds, window, trustHeardFlag)?.let { RfScanEntry(node, it) }
        }
        val excluded = others.count { isNetworkOnly(it) && isInWindow(it, nowSeconds, window) }
        return RfScanResult(entries = entries.sortedWith(comparatorFor(sort)), excludedNetworkCount = excluded)
    }

    private fun comparatorFor(sort: RfScanSort): Comparator<RfScanEntry> = when (sort) {
        RfScanSort.LAST_HEARD -> compareByDescending<RfScanEntry> { it.node.lastHeard }.thenBy { it.node.num }

        // Only a direct node's SNR is its own; a relayed node's stored SNR is stale, so it is never ranked on it.
        RfScanSort.SIGNAL ->
            compareBy<RfScanEntry> { it.link.ordinal }
                .thenByDescending {
                    if (it.link == RfLink.DIRECT) it.node.snrOrNull ?: Float.NEGATIVE_INFINITY else 0f
                }
                .thenBy { if (it.link == RfLink.RELAYED) it.node.hopsAway else 0 }
                .thenByDescending { it.node.lastHeard }
                .thenBy { it.node.num }
    }
}

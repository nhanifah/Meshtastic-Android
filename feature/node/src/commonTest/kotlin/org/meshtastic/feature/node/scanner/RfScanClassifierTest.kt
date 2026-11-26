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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RfScanClassifierTest {

    private val now = 1_800_000_000L
    private val ourNum = 1

    @Suppress("LongParameterList")
    private fun node(
        num: Int,
        agoSeconds: Long = 60,
        hopsAway: Int = 0,
        viaMqtt: Boolean = false,
        transport: MeshPacket.TransportMechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA,
        snr: Float = Node.SNR_UNSET,
        heardOnCurrentLora: Boolean = true,
        isIgnored: Boolean = false,
    ) = Node(
        num = num,
        lastHeard = (now - agoSeconds).toInt(),
        hopsAway = hopsAway,
        viaMqtt = viaMqtt,
        lastTransport = transport.value,
        snr = snr,
        heardOnCurrentLora = heardOnCurrentLora,
        isIgnored = isIgnored,
    )

    private fun classify(node: Node, window: RfScanWindow = RfScanWindow.HOUR_1, trust: Boolean = true) =
        RfScanClassifier.classify(node, now, window, trust)

    private fun scan(
        nodes: List<Node>,
        window: RfScanWindow = RfScanWindow.HOUR_1,
        sort: RfScanSort = RfScanSort.SIGNAL,
        trust: Boolean = true,
    ) = RfScanClassifier.scan(nodes, ourNum, now, window, sort, trust)

    @Test
    fun zeroHopsIsDirect() {
        assertEquals(RfLink.DIRECT, classify(node(2, hopsAway = 0)))
    }

    @Test
    fun oneOrMoreHopsIsRelayed() {
        assertEquals(RfLink.RELAYED, classify(node(2, hopsAway = 1)))
        assertEquals(RfLink.RELAYED, classify(node(2, hopsAway = 5)))
    }

    @Test
    fun unknownHopsStaysSeparate() {
        assertEquals(RfLink.UNKNOWN_HOPS, classify(node(2, hopsAway = -1)))
    }

    @Test
    fun viaMqttIsNeverRf() {
        assertNull(classify(node(2, viaMqtt = true)))
    }

    @Test
    fun mqttOrUdpTransportIsNeverRf() {
        // A gateway can relay an MQTT packet over the air; the transport still says where it came from.
        assertNull(classify(node(2, transport = MeshPacket.TransportMechanism.TRANSPORT_MQTT)))
        assertNull(classify(node(3, transport = MeshPacket.TransportMechanism.TRANSPORT_MULTICAST_UDP)))
    }

    @Test
    fun internalTransportStillCountsForOlderFirmware() {
        // Firmware that never sets transport_mechanism leaves it at 0, which must not hide every node.
        assertEquals(RfLink.DIRECT, classify(node(2, transport = MeshPacket.TransportMechanism.TRANSPORT_INTERNAL)))
    }

    @Test
    fun windowEdgeIsInclusive() {
        assertEquals(RfLink.DIRECT, classify(node(2, agoSeconds = RfScanWindow.HOUR_1.seconds)))
        assertNull(classify(node(2, agoSeconds = RfScanWindow.HOUR_1.seconds + 1)))
    }

    @Test
    fun widerWindowAdmitsOlderNodes() {
        val old = node(2, agoSeconds = 3 * 60 * 60L)
        assertNull(classify(old, RfScanWindow.HOURS_2))
        assertEquals(RfLink.DIRECT, classify(old, RfScanWindow.HOURS_6))
    }

    @Test
    fun neverHeardIsExcluded() {
        assertNull(classify(Node(num = 2, lastHeard = 0, hopsAway = 0)))
    }

    @Test
    fun futureTimestampCountsAsNow() {
        assertEquals(RfLink.DIRECT, classify(node(2, agoSeconds = -30)))
    }

    @Test
    fun unheardOnCurrentLoraOnlyHidesWhenTrusted() {
        val unheard = node(2, heardOnCurrentLora = false)
        assertNull(classify(unheard, trust = true))
        assertEquals(RfLink.DIRECT, classify(unheard, trust = false))
    }

    @Test
    fun scanSkipsOurNodeAndIgnoredNodes() {
        val result = scan(listOf(node(ourNum), node(2, isIgnored = true), node(3)))
        assertEquals(listOf(3), result.entries.map { it.node.num })
    }

    @Test
    fun scanCountsEachLinkAndExcludedMqtt() {
        val result =
            scan(
                listOf(
                    node(2, hopsAway = 0),
                    node(3, hopsAway = 0),
                    node(4, hopsAway = 2),
                    node(5, hopsAway = -1),
                    node(6, viaMqtt = true),
                    node(7, transport = MeshPacket.TransportMechanism.TRANSPORT_MQTT),
                    // MQTT but outside the window: neither counted nor reported as excluded.
                    node(8, viaMqtt = true, agoSeconds = 10 * 60 * 60L),
                    // RF but outside the window.
                    node(9, agoSeconds = 10 * 60 * 60L),
                ),
            )
        assertEquals(2, result.directCount)
        assertEquals(1, result.relayedCount)
        assertEquals(1, result.unknownHopsCount)
        assertEquals(4, result.total)
        assertEquals(2, result.excludedNetworkCount)
    }

    @Test
    fun signalSortPutsStrongestDirectFirstThenFewestHops() {
        val result =
            scan(
                listOf(
                    node(2, hopsAway = 3),
                    node(3, hopsAway = 0, snr = -5f),
                    node(4, hopsAway = 1),
                    node(5, hopsAway = 0, snr = 8f),
                    node(6, hopsAway = -1),
                    node(7, hopsAway = 0),
                ),
            )
        assertEquals(listOf(5, 3, 7, 4, 2, 6), result.entries.map { it.node.num })
    }

    @Test
    fun lastHeardSortIsNewestFirst() {
        val result =
            scan(
                listOf(node(2, agoSeconds = 300), node(3, agoSeconds = 10), node(4, agoSeconds = 100, hopsAway = 2)),
                sort = RfScanSort.LAST_HEARD,
            )
        assertEquals(listOf(3, 4, 2), result.entries.map { it.node.num })
    }
}

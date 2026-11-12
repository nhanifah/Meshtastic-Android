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
package org.meshtastic.feature.rangetest

import org.meshtastic.core.database.entity.RangeTestLocationSource
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSampleKind
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.PhoneLocation
import org.meshtastic.core.repository.PhoneLocationState
import org.meshtastic.core.ui.util.RangeTestMapMode
import org.meshtastic.core.ui.util.RangeTestMapTarget
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeTestLogicTest {

    @Test
    fun parsesFirmwareSequenceNumbers() {
        assertEquals(42, RangeTestSignal.parseSeq("seq 42"))
        assertEquals(7, RangeTestSignal.parseSeq("SEQ  7"))
        assertNull(RangeTestSignal.parseSeq("hello"))
    }

    @Test
    fun hopsComeFromHopStartMinusLimit() {
        assertEquals(0, RangeTestSignal.hops(packet(hopStart = 3, hopLimit = 3)))
        assertEquals(2, RangeTestSignal.hops(packet(hopStart = 3, hopLimit = 1)))
        assertNull(RangeTestSignal.hops(packet(hopStart = 0, hopLimit = 3)))
    }

    @Test
    fun bandsAndWeaknessFollowThresholds() {
        assertEquals(SignalBand.STRONG, RangeTestSignal.band(RangeTestMetric.SNR, 8f))
        assertEquals(SignalBand.VERY_WEAK, RangeTestSignal.band(RangeTestMetric.SNR, -15f))
        assertEquals(SignalBand.FAIR, RangeTestSignal.band(RangeTestMetric.RSSI, -110f))
        assertEquals(0.0, RangeTestSignal.weakness(RangeTestMetric.SNR, 10f))
        assertEquals(1.0, RangeTestSignal.weakness(RangeTestMetric.RSSI, -140f))
    }

    @Test
    fun relayedBeaconsAreExcludedUnlessAsked() {
        val samples = listOf(received(hops = 0), received(hops = 2), received(hops = null), missed())
        assertEquals(3, RangeTestGrid.signalSamples(samples, includeRelayed = false).size)
        assertEquals(4, RangeTestGrid.signalSamples(samples, includeRelayed = true).size)
    }

    @Test
    fun cellsAggregateAndLossDowngradesBand() {
        val samples = listOf(received(snr = 6f), missed(), missed(), missed())
        val cells = RangeTestGrid.cells(samples, originLat = LAT, originLon = LON, cellSizeM = 50)
        assertEquals(1, cells.size)
        val cell = cells.single()
        assertEquals(1, cell.received)
        assertEquals(3, cell.missed)
        // Strong SNR but 25% delivery: the cell is still a weak spot.
        assertEquals(SignalBand.WEAK, RangeTestGrid.cellBand(cell, RangeTestMetric.SNR))
    }

    @Test
    fun cellsSplitByCellSize() {
        val far = received().copy(latitude = LAT + 0.001) // ~111 m north
        val cells = RangeTestGrid.cells(listOf(received(), far), originLat = LAT, originLon = LON, cellSizeM = 50)
        assertEquals(2, cells.size)
    }

    @Test
    fun summaryCountsDirectOnlyAndBuckets() {
        val samples =
            listOf(
                received(hops = 0, distance = 300.0),
                received(hops = 1, distance = 800.0),
                missed(distance = 1_500.0),
            )
        val summary = RangeTestGrid.summary(samples)
        assertEquals(1, summary.received)
        assertEquals(1, summary.relayed)
        assertEquals(1, summary.missed)
        assertEquals(300.0, summary.maxDirectDistanceM)
        assertEquals(listOf(0, 1_000), summary.buckets.map { it.fromM })
    }

    @Test
    fun heatmapWeightsMissedAsHottest() {
        val (data, _) =
            RangeTestGrid.mapData(
                samples = listOf(received(snr = 10f), missed()),
                target = RangeTestMapTarget(LAT, LON, "base"),
                mode = RangeTestMapMode.HEATMAP,
                metric = RangeTestMetric.SNR,
                cellSizeM = 50,
                includeRelayed = false,
                receiver = null,
            )
        assertEquals(listOf(0.0, 1.0), data.heat.map { it.weight })
        assertTrue(data.ringsMeters.isNotEmpty())
    }

    @Test
    fun radioFixWinsOnlyWhileFresh() {
        val now = 1_000_000_000L
        val phone = PhoneLocationState(true, true, PhoneLocation(1.0, 2.0, null, now, null, 5f, null, null))
        val radioNode =
            Node(
                num = 1,
                position =
                Position.Builder()
                    .latitude_i(10_000_000)
                    .longitude_i(20_000_000)
                    .time((now / 1000).toInt())
                    .build(),
            )
        assertEquals(RangeTestLocationSource.RADIO, RangeTestRecorder.bestFix(phone, radioNode, now)?.source)
        val stale = now + RangeTestRecorder.RADIO_FIX_MAX_AGE_MS + 1_000
        assertEquals(RangeTestLocationSource.PHONE, RangeTestRecorder.bestFix(phone, radioNode, stale)?.source)
        assertEquals(RangeTestLocationSource.PHONE, RangeTestRecorder.bestFix(phone, null, now)?.source)
    }

    private fun packet(hopStart: Int, hopLimit: Int) =
        MeshPacket.Builder().hop_start(hopStart).hop_limit(hopLimit).build()

    private fun received(hops: Int? = 0, snr: Float? = 0f, distance: Double? = 100.0) = RangeTestSampleEntity(
        sessionId = 1,
        kind = RangeTestSampleKind.RECEIVED,
        timeMillis = 0,
        latitude = LAT,
        longitude = LON,
        locationSource = RangeTestLocationSource.PHONE,
        snr = snr,
        rssi = -100,
        hops = hops,
        distanceM = distance,
    )

    private fun missed(distance: Double? = 100.0) = RangeTestSampleEntity(
        sessionId = 1,
        kind = RangeTestSampleKind.MISSED,
        timeMillis = 0,
        latitude = LAT,
        longitude = LON,
        locationSource = RangeTestLocationSource.PHONE,
        distanceM = distance,
    )

    private companion object {
        const val LAT = -6.2001
        const val LON = 106.8001
    }
}

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

import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.database.entity.RangeTestLocationSource
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSampleKind
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.PhoneLocation
import org.meshtastic.core.repository.PhoneLocationState
import org.meshtastic.core.ui.util.RangeTestMapPin
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
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
    fun bandsAndStrengthFollowThresholds() {
        assertEquals(SignalBand.STRONG, RangeTestSignal.band(RangeTestMetric.SNR, 8f))
        assertEquals(SignalBand.VERY_WEAK, RangeTestSignal.band(RangeTestMetric.SNR, -15f))
        assertEquals(SignalBand.FAIR, RangeTestSignal.band(RangeTestMetric.RSSI, -110f))
        assertEquals(0f, RangeTestSignal.strength(RangeTestMetric.RSSI, -140f))
        assertEquals(1f, RangeTestSignal.strength(RangeTestMetric.SNR, 20f))
        // Each band covers a fifth of the scale: a FAIR reading lands between 0.4 and 0.6.
        val fair = RangeTestSignal.strength(RangeTestMetric.SNR, -3f)
        assertTrue(fair in 0.4f..0.6f, "fair strength was $fair")
    }

    @Test
    fun relayedBeaconsAreExcludedUnlessAsked() {
        val samples = listOf(received(hops = 0), received(hops = 2), received(hops = null), missed())
        assertEquals(3, RangeTestGrid.signalSamples(samples, includeRelayed = false).size)
        assertEquals(4, RangeTestGrid.signalSamples(samples, includeRelayed = true).size)
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
    fun relayedBeaconAdvancesSequenceSoItIsNeverMissed() {
        val sequence = BeaconSequence()
        assertTrue(sequence.advance(seq = 1, now = 0).isEmpty())
        // Beacon 2 arrived through a relay: still received, so it must not show up as a gap later.
        assertTrue(sequence.advance(seq = 2, now = 15_000).isEmpty())
        assertTrue(sequence.advance(seq = 3, now = 30_000).isEmpty())
    }

    @Test
    fun skippedSequenceNumbersBecomeGapsWithInterpolatedTimes() {
        val sequence = BeaconSequence()
        sequence.advance(seq = 10, now = 0)
        val gaps = sequence.advance(seq = 13, now = 30_000)
        assertEquals(listOf(11, 12), gaps.map { it.seq })
        assertEquals(listOf(10_000L, 20_000L), gaps.map { it.dueAt })
    }

    @Test
    fun duplicatesAndRestartsAreNotLosses() {
        val sequence = BeaconSequence()
        sequence.advance(seq = 50, now = 0)
        assertTrue(sequence.advance(seq = 50, now = 1_000).isEmpty())
        // The sender restarted from 1: a new run, not 49 lost beacons.
        assertTrue(sequence.advance(seq = 1, now = 2_000).isEmpty())
        assertEquals(listOf(2), sequence.advance(seq = 3, now = 4_000).map { it.seq })
    }

    @Test
    fun parsesOwnAndFirmwareBeacons() {
        val own = beaconPacket(PortNum.PRIVATE_APP, RangeTestBeacon.encode(seq = 5, intervalSec = 15).utf8())
        assertEquals(RangeTestBeacon.Parsed(seq = 5, intervalSec = 15), RangeTestBeacon.parse(own))
        val firmware = beaconPacket(PortNum.RANGE_TEST_APP, "seq 9")
        assertEquals(9, RangeTestBeacon.parse(firmware)?.seq)
        assertNull(RangeTestBeacon.parse(beaconPacket(PortNum.PRIVATE_APP, "something else")))
        assertNull(RangeTestBeacon.parse(beaconPacket(PortNum.TEXT_MESSAGE_APP, "seq 1")))
    }

    @Test
    fun heatmapColoursBySignalAndLeavesUnvisitedAreasTransparent() {
        val strong = RangeTestHeatInput(LAT, LON, strength = 1f)
        val weak = RangeTestHeatInput(LAT + 0.01, LON, strength = 0f) // ~1.1 km north
        val raster = RangeTestHeatmap.render(listOf(strong, weak), version = 1)!!
        val pixels = raster.pixels
        // Some pixels are drawn, and some (between the far-apart readings) are left fully transparent.
        assertTrue(pixels.any { it ushr 24 != 0 })
        assertTrue(pixels.any { it ushr 24 == 0 })
        // The bottom (south, strong) half is greener than the top (north, weak) half.
        fun greenMinusRed(rows: IntRange): Int = rows.sumOf { row ->
            (0 until raster.width).sumOf { col ->
                val p = pixels[row * raster.width + col]
                if (p ushr 24 == 0) 0 else ((p shr 8) and 0xFF) - ((p shr 16) and 0xFF)
            }
        }
        val half = raster.height / 2
        assertTrue(greenMinusRed(half until raster.height) > greenMinusRed(0 until half))
    }

    @Test
    fun heatmapIsEmptyWithoutReadings() {
        assertNull(RangeTestHeatmap.render(emptyList(), version = 1))
    }

    @Test
    fun mapDataShowsRouteOnlyWhenAsked() {
        val track = received().copy(kind = RangeTestSampleKind.TRACK)
        val samples = listOf(received(snr = 8f), track, track.copy(latitude = LAT + 0.001))
        fun mapData(showRoute: Boolean) = RangeTestGrid.mapData(
            samples = samples,
            sender = RangeTestMapPin(LAT, LON, "base"),
            receiver = null,
            metric = RangeTestMetric.SNR,
            includeRelayed = false,
            showRoute = showRoute,
            version = 1,
        )
        val shown = mapData(showRoute = true)
        assertEquals(2, shown.track.size)
        assertTrue(shown.heat != null)
        assertTrue(mapData(showRoute = false).track.isEmpty())
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

    private fun beaconPacket(port: PortNum, text: String) =
        MeshPacket.Builder().decoded(Data.Builder().portnum(port).payload(text.encodeUtf8()).build()).build()

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

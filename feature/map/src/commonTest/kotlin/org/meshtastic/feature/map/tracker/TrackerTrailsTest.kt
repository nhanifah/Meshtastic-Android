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
package org.meshtastic.feature.map.tracker

import org.meshtastic.core.common.util.bearing
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.model.MeshLog
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerTrailsTest {

    private val now = 1_800_000_000L
    private val hour = 3_600L

    // 1e-4 degrees of latitude is about 11 m.
    private fun fix(secondsAgo: Long, latitude: Double, longitude: Double = 106.8, speedKmh: Int? = null) = TrackerFix(
        latitude = latitude,
        longitude = longitude,
        timeSeconds = now - secondsAgo,
        groundSpeedKmh = speedKmh,
    )

    /** A node walking north, one fix a minute, about 66 m apart (1.1 m/s). */
    private fun walk(minutes: Int, startSecondsAgo: Long = minutes * 60L) =
        (0 until minutes).map { i -> fix(secondsAgo = startSecondsAgo - i * 60L, latitude = -6.2 + i * 6e-4) }

    @Test
    fun `nothing inside the tail is no trail`() {
        val fixes = listOf(fix(secondsAgo = 2 * hour, latitude = -6.2))

        assertNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))
    }

    @Test
    fun `the tail drops fixes older than its length`() {
        val trail = assertNotNull(buildNodeTrail(1, walk(minutes = 120), now, tailSeconds = 30 * 60L))

        assertTrue(trail.points.all { it.timeSeconds >= now - 30 * 60L })
        assertEquals(30, trail.points.size)
    }

    @Test
    fun `a fix from a clock far in the future is not drawn`() {
        val fixes = walk(minutes = 5) + fix(secondsAgo = -hour, latitude = -6.0)

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))

        assertTrue(trail.points.none { it.timeSeconds > now })
    }

    @Test
    fun `a null island fix is not drawn`() {
        val fixes = walk(minutes = 3) + TrackerFix(0.0, 0.0, now - 10)

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))

        assertTrue(trail.points.none { it.latitude == 0.0 && it.longitude == 0.0 })
    }

    @Test
    fun `a parked node collapses to one dot and draws no line`() {
        val fixes = (0 until 60).map { i -> fix(secondsAgo = 3_600L - i * 60L, latitude = -6.2 + (i % 2) * 5e-5) }

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = 2 * hour))

        assertEquals(1, trail.points.size)
        assertTrue(trail.segments.isEmpty())
        assertNull(trail.direction)
    }

    @Test
    fun `a steady walk is one solid segment through every fix`() {
        val trail = assertNotNull(buildNodeTrail(1, walk(minutes = 10), now, tailSeconds = hour))

        assertEquals(1, trail.segments.size)
        assertFalse(trail.segments.single().jump)
        assertEquals(trail.points, trail.segments.single().points)
    }

    @Test
    fun `a long silence between fixes is a dashed jump`() {
        val before = walk(minutes = 5, startSecondsAgo = 3 * hour)
        val after = walk(minutes = 5, startSecondsAgo = 10 * 60L).map { it.copy(latitude = it.latitude + 0.05) }

        val trail = assertNotNull(buildNodeTrail(1, before + after, now, tailSeconds = 6 * hour))

        assertEquals(listOf(false, true, false), trail.segments.map { it.jump })
        assertEquals(2, trail.segments[1].points.size)
    }

    @Test
    fun `an impossible speed is a dashed jump`() {
        // About 11 km in one minute: 185 m/s.
        val fixes = listOf(fix(120, -6.2), fix(60, -6.2006), fix(0, -6.3006))

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))

        assertEquals(listOf(false, true), trail.segments.map { it.jump })
    }

    @Test
    fun `a node that moves and was heard recently points where it is heading`() {
        val trail =
            assertNotNull(buildNodeTrail(1, walk(minutes = 10, startSecondsAgo = 600L), now, tailSeconds = hour))

        val direction = assertNotNull(trail.direction)
        // Walking north.
        assertTrue(direction.headingDeg < 1.0 || direction.headingDeg > 359.0, "heading ${direction.headingDeg}")
        val length =
            latLongToMeter(
                direction.from.latitude,
                direction.from.longitude,
                direction.toLatitude,
                direction.toLongitude,
            )
        assertTrue(length in TrackerRules.DIRECTION_MIN_METERS..TrackerRules.DIRECTION_MAX_METERS, "length $length")
    }

    @Test
    fun `a moving node not heard for a while gets no direction line`() {
        val trail =
            assertNotNull(
                buildNodeTrail(1, walk(minutes = 10, startSecondsAgo = 2 * hour), now, tailSeconds = 3 * hour),
            )

        assertNull(trail.direction)
    }

    @Test
    fun `a reported speed of zero means parked even when fixes wander`() {
        val fixes = walk(minutes = 10, startSecondsAgo = 600L).map { it.copy(groundSpeedKmh = 0) }

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))

        assertNull(trail.direction)
        assertEquals(0.0, trail.speedMetersPerSecond)
    }

    @Test
    fun `a reported ground track wins over the bearing between fixes`() {
        val fixes =
            walk(minutes = 10, startSecondsAgo = 600L).map { it.copy(groundSpeedKmh = 36, groundTrackDeg = 90.0) }

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour))

        assertEquals(90.0, assertNotNull(trail.direction).headingDeg)
        assertEquals(10.0, assertNotNull(trail.speedMetersPerSecond), 1e-9)
    }

    @Test
    fun `only the newest fixes are kept for a chatty node`() {
        val rules = TrackerRules(maxPointsPerNode = 20)
        val fixes = (0 until 100).map { i -> fix(secondsAgo = 100L - i, latitude = -6.2 + i * 1e-3) }

        val trail = assertNotNull(buildNodeTrail(1, fixes, now, tailSeconds = hour, rules = rules))

        assertEquals(20, trail.points.size)
        assertEquals(now - 1, trail.points.last().timeSeconds)
    }

    @Test
    fun `destination point travels the asked distance along the asked bearing`() {
        val (lat, lon) = destinationPoint(-6.2, 106.8, bearingDeg = 45.0, distanceMeters = 1_000.0)

        assertEquals(1_000.0, latLongToMeter(-6.2, 106.8, lat, lon), 0.5)
        assertTrue(abs(bearing(-6.2, 106.8, lat, lon) - 45.0) < 0.1)
    }

    @Test
    fun `logs from this device fold into the local node's own trail`() {
        val myNum = 42
        val logs =
            listOf(
                positionLog(fromNum = MeshLog.NODE_NUM_LOCAL, latitude = -6.2, secondsAgo = 120),
                positionLog(fromNum = myNum, latitude = -6.201, secondsAgo = 60),
                positionLog(fromNum = 7, latitude = -6.3, secondsAgo = 60),
            )

        val trails = buildTrails(logs, myNodeNum = myNum, nowSeconds = now, tailSeconds = hour)

        assertEquals(listOf(7, myNum), trails.map { it.nodeNum })
        assertEquals(2, trails.single { it.nodeNum == myNum }.points.size)
    }

    @Test
    fun `a fix without a clock takes the time it was logged`() {
        val log = positionLog(fromNum = 7, latitude = -6.2, secondsAgo = 60, positionTime = 0)

        val trail = buildTrails(listOf(log), myNodeNum = null, nowSeconds = now, tailSeconds = hour).single()

        assertEquals(now - 60, trail.points.single().timeSeconds)
    }

    private fun positionLog(fromNum: Int, latitude: Double, secondsAgo: Long, positionTime: Int? = null): MeshLog {
        val time = now - secondsAgo
        val position =
            Position.Builder()
                .also { b ->
                    b.latitude_i = (latitude * 1e7).toInt()
                    b.longitude_i = (106.8 * 1e7).toInt()
                    b.time = positionTime ?: time.toInt()
                }
                .build()
        val data =
            Data.Builder()
                .also { b ->
                    b.portnum = PortNum.POSITION_APP
                    b.payload = Position.ADAPTER.encodeByteString(position)
                }
                .build()
        val packet =
            MeshPacket.Builder()
                .also { b ->
                    b.from = fromNum
                    b.decoded = data
                }
                .build()
        return MeshLog(
            uuid = "$fromNum-$secondsAgo",
            message_type = "Packet",
            received_date = time * 1000,
            raw_message = "",
            fromNum = fromNum,
            portNum = PortNum.POSITION_APP.value,
            fromRadio = FromRadio.Builder().also { it.packet = packet }.build(),
        )
    }
}

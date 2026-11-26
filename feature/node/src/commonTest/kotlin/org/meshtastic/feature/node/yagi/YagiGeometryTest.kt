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
package org.meshtastic.feature.node.yagi

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YagiGeometryTest {

    @Test
    fun level_path_dips_below_horizon_for_earth_curvature() {
        // Two stations at the same height 20 km apart: the far one sits below the horizon by d/(2kR).
        val e = YagiGeometry.elevationDegrees(100.0, 100.0, 20_000.0)!!
        assertEquals(-0.0672, e, 0.001)
    }

    @Test
    fun higher_target_is_above_horizon() {
        // 100 m up over 1 km, curvature negligible: atan(0.1) = 5.71°.
        val e = YagiGeometry.elevationDegrees(0.0, 100.0, 1_000.0)!!
        assertEquals(5.71, e, 0.01)
    }

    @Test
    fun return_elevation_is_not_just_the_negative() {
        val up = YagiGeometry.elevationDegrees(0.0, 300.0, 30_000.0)!!
        val back = YagiGeometry.elevationDegrees(300.0, 0.0, 30_000.0)!!
        // Curvature pulls both ends down, so the pair sums to a small negative angle rather than zero.
        assertTrue(up + back < 0)
        val curvature = 30_000.0 / (2 * YagiGeometry.EARTH_RADIUS_M * YagiGeometry.RADIO_K_FACTOR)
        assertEquals(-2 * curvature, (up + back) * PI / 180.0, 1e-5)
    }

    @Test
    fun zero_distance_has_no_elevation() {
        assertNull(YagiGeometry.elevationDegrees(0.0, 10.0, 0.0))
    }

    @Test
    fun signed_delta_takes_the_short_way_round() {
        assertEquals(20.0, YagiGeometry.signedDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, YagiGeometry.signedDelta(10.0, 350.0), 1e-9)
        assertEquals(180.0, YagiGeometry.signedDelta(0.0, 180.0), 1e-9)
        assertEquals(-90.0, YagiGeometry.signedDelta(90.0, 0.0), 1e-9)
    }

    @Test
    fun back_azimuth_wraps() {
        assertEquals(10.0, YagiGeometry.backAzimuth(190.0), 1e-9)
        assertEquals(270.0, YagiGeometry.backAzimuth(90.0), 1e-9)
    }

    @Test
    fun smoothing_crosses_north_without_swinging_through_south() {
        val s = YagiGeometry.smoothAngle(359.0, 1.0, 0.5)
        assertTrue(s < 0.01 || s > 359.99, "got $s")
        assertEquals(42.0, YagiGeometry.smoothAngle(null, 42.0, 0.2), 1e-9)
    }

    @Test
    fun bulge_at_midpoint() {
        // 50 km, k = 4/3: 50000² / (8 · 6371 km · 4/3) ≈ 36.8 m.
        assertEquals(36.8, YagiGeometry.midpointBulgeMeters(50_000.0), 0.1)
    }

    @Test
    fun standard_atmosphere_altitude() {
        assertEquals(0.0, YagiGeometry.pressureAltitudeMeters(1013.25), 0.01)
        // ISA: about 899 hPa at 1000 m.
        assertEquals(1000.0, YagiGeometry.pressureAltitudeMeters(898.75), 5.0)
    }
}

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
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Pure pointing math for aiming a directional (Yagi) antenna at another node. */
object YagiGeometry {
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Effective-earth factor for VHF/UHF paths in a standard atmosphere: refraction bends the beam down, which is the
     * same as a flatter earth 4/3 the real size.
     */
    const val RADIO_K_FACTOR = 4.0 / 3.0

    private const val FULL_CIRCLE = 360.0
    private const val HALF_CIRCLE = 180.0

    /** ISA sea-level pressure and the barometric formula's constants, for a phone barometer with no reference. */
    private const val SEA_LEVEL_HPA = 1013.25
    private const val ISA_HEIGHT_SCALE_M = 44_330.77
    private const val ISA_EXPONENT = 0.190263

    /** Sagitta of a chord: h = d² / (8R). */
    private const val MIDPOINT_BULGE_DIVISOR = 8.0

    /**
     * Angle above (positive) or below the horizon at which an antenna at [fromAltitudeM] sees one at [toAltitudeM]
     * [distanceM] away along the ground, with the earth's bulge (scaled by [kFactor]) taken off. Null for a zero or
     * negative distance, where no direction exists.
     */
    fun elevationDegrees(
        fromAltitudeM: Double,
        toAltitudeM: Double,
        distanceM: Double,
        kFactor: Double = RADIO_K_FACTOR,
    ): Double? {
        if (distanceM <= 0.0) return null
        val effectiveRadius = EARTH_RADIUS_M * kFactor
        val slope = (toAltitudeM - fromAltitudeM) / distanceM - distanceM / (2.0 * effectiveRadius)
        return atan(slope).toDegrees()
    }

    /** How far the earth rises above the straight chord at the middle of a [distanceM] path, in metres. */
    fun midpointBulgeMeters(distanceM: Double, kFactor: Double = RADIO_K_FACTOR): Double =
        distanceM * distanceM / (MIDPOINT_BULGE_DIVISOR * EARTH_RADIUS_M * kFactor)

    /**
     * Height above sea level in the standard atmosphere for a station pressure of [pressureHpa]. Uncalibrated: the
     * day's weather moves sea-level pressure enough to shift this by tens of metres, so it is a last resort behind GPS.
     */
    fun pressureAltitudeMeters(pressureHpa: Double, seaLevelHpa: Double = SEA_LEVEL_HPA): Double =
        ISA_HEIGHT_SCALE_M * (1.0 - (pressureHpa / seaLevelHpa).pow(ISA_EXPONENT))

    /** [degrees] folded into 0 until 360. */
    fun normalize(degrees: Double): Double {
        val r = degrees % FULL_CIRCLE
        return if (r < 0) r + FULL_CIRCLE else r
    }

    /** The bearing the other end points back along. */
    fun backAzimuth(azimuth: Double): Double = normalize(azimuth + HALF_CIRCLE)

    /**
     * Shortest signed turn from [from] to [to], in -180 exclusive to 180 inclusive degrees: positive means turn
     * clockwise (to the right).
     */
    fun signedDelta(from: Double, to: Double): Double {
        val d = normalize(to - from)
        return if (d > HALF_CIRCLE) d - FULL_CIRCLE else d
    }

    /**
     * Low-pass on a compass angle that stays correct across north: averages the unit vectors rather than the numbers,
     * so 359° and 1° settle on 0°, not 180°. [alpha] is the weight of [next] (0..1).
     */
    fun smoothAngle(previous: Double?, next: Double, alpha: Double): Double {
        if (previous == null) return normalize(next)
        val p = previous.toRadians()
        val n = next.toRadians()
        val x = (1 - alpha) * cos(p) + alpha * cos(n)
        val y = (1 - alpha) * sin(p) + alpha * sin(n)
        return normalize(atan2(y, x).toDegrees())
    }

    private fun Double.toDegrees(): Double = this * HALF_CIRCLE / PI

    private fun Double.toRadians(): Double = this * PI / HALF_CIRCLE
}

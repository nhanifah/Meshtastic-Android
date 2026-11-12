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
package org.meshtastic.core.ui.util

import androidx.compose.runtime.Immutable

/** How the range-test map presents the recorded samples. */
enum class RangeTestMapMode {
    /** Square cells colored by the cell's average signal — the view for finding weak spots. */
    GRID,

    /** A smooth heatmap whose intensity is signal *weakness*, so hot areas are weak areas. */
    HEATMAP,

    /** Every received / missed beacon as its own dot. */
    POINTS,
}

@Immutable data class RangeTestLatLng(val latitude: Double, val longitude: Double)

/** One aggregated grid cell; [argb] is the precomputed fill (alpha included). */
@Immutable
data class RangeTestMapCell(
    val id: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val argb: Int,
)

/** One beacon dot. [missed] dots are drawn hollow/dark; [argb] is the signal color for received ones. */
@Immutable data class RangeTestMapPoint(val latitude: Double, val longitude: Double, val argb: Int, val missed: Boolean)

/** One heatmap contribution; [weight] is in 0..1 where 1 is the weakest signal (or a missed beacon). */
@Immutable data class RangeTestHeatPoint(val latitude: Double, val longitude: Double, val weight: Double)

@Immutable data class RangeTestMapTarget(val latitude: Double, val longitude: Double, val label: String)

/**
 * Everything a platform map needs to draw a range-test session, already bucketed and colored in common code so each map
 * engine (Google Maps, MapLibre) stays a thin renderer.
 */
@Immutable
data class RangeTestMapData(
    val mode: RangeTestMapMode = RangeTestMapMode.GRID,
    val target: RangeTestMapTarget? = null,
    val ringsMeters: List<Double> = emptyList(),
    val cells: List<RangeTestMapCell> = emptyList(),
    val points: List<RangeTestMapPoint> = emptyList(),
    val heat: List<RangeTestHeatPoint> = emptyList(),
    val track: List<RangeTestLatLng> = emptyList(),
    /** The receiver's latest position while recording, null for a finished session. */
    val receiver: RangeTestLatLng? = null,
) {
    /** Every coordinate worth framing, for the initial camera fit. */
    val boundsPoints: List<RangeTestLatLng>
        get() = buildList {
            target?.let { add(RangeTestLatLng(it.latitude, it.longitude)) }
            addAll(track)
            points.forEach { add(RangeTestLatLng(it.latitude, it.longitude)) }
            receiver?.let { add(it) }
        }
}

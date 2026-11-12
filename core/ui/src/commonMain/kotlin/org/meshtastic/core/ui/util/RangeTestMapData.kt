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

/** A WGS84 coordinate in the range-test map model. */
data class RangeTestLatLng(val latitude: Double, val longitude: Double)

/** A labelled pin: the stationary sender or the receiver's latest position. */
data class RangeTestMapPin(val latitude: Double, val longitude: Double, val label: String)

/**
 * The signal heatmap as a ready-to-drape image: [pixels] holds [width] × [height] ARGB values, row by row from the
 * north-west corner, covering the box [south]..[north] × [west]..[east]. Colour is the interpolated signal level, and
 * pixels far from any measurement are fully transparent.
 *
 * [version] changes whenever the content does, so renderers can rebuild their bitmap only when needed (an [IntArray]
 * has no value equality).
 */
class RangeTestHeatRaster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val version: Long,
) {
    override fun equals(other: Any?): Boolean = other is RangeTestHeatRaster && other.version == version

    override fun hashCode(): Int = version.hashCode()
}

/**
 * Everything a range-test map draws: the signal heatmap, the optional route, and two pins. No circles or rings: the
 * heatmap alone shows where the signal is strong or weak.
 */
data class RangeTestMapData(
    val heat: RangeTestHeatRaster? = null,
    val track: List<RangeTestLatLng> = emptyList(),
    val sender: RangeTestMapPin? = null,
    val receiver: RangeTestMapPin? = null,
) {
    /** Points the camera should frame on first show. */
    val boundsPoints: List<RangeTestLatLng>
        get() = buildList {
            heat?.let {
                add(RangeTestLatLng(it.south, it.west))
                add(RangeTestLatLng(it.north, it.east))
            }
            addAll(track)
            sender?.let { add(RangeTestLatLng(it.latitude, it.longitude)) }
            receiver?.let { add(RangeTestLatLng(it.latitude, it.longitude)) }
        }
}

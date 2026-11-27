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
package org.meshtastic.app.map

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.ButtCap
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberUpdatedMarkerState
import org.meshtastic.core.repository.TrackerSettings
import org.meshtastic.feature.map.trackFadeLevel
import org.meshtastic.feature.map.trackFadeLevelAlpha
import org.meshtastic.feature.map.trackRecency
import org.meshtastic.feature.map.tracker.NodeTrail
import org.meshtastic.feature.map.tracker.TrailPoint
import kotlin.math.roundToInt

/**
 * The tracker's trails on the Google map, drawn by the same rules as the MapLibre map's `TrackerLayers`: solid where a
 * node travelled, dashed where it jumped, a dot on every fix and a short line ahead of a moving node.
 *
 * Dots are markers, and every marker is added on the main thread, so only the newest [MAX_TRACKER_DOTS] across all
 * trails get one; the lines carry the rest of the history.
 *
 * @param nodeColors Node number to the node's background colour, as packed ARGB.
 */
@Composable
internal fun TrackerOverlay(trails: List<NodeTrail>, nodeColors: Map<Int, Int>, settings: TrackerSettings) {
    trails.forEach { trail ->
        val color = Color(nodeColors[trail.nodeNum] ?: FALLBACK_COLOR)
        key(trail.nodeNum) {
            trail.segments.forEachIndexed { index, segment ->
                key(index, segment.jump) {
                    if (!segment.jump) {
                        Polyline(
                            points = segment.points.map { it.toLatLng() },
                            color = color.copy(alpha = LINE_ALPHA),
                            width = LINE_WIDTH_PX,
                            jointType = JointType.ROUND,
                            startCap = RoundCap(),
                            endCap = RoundCap(),
                            zIndex = TRAIL_Z_INDEX,
                        )
                    } else if (settings.showJumps) {
                        Polyline(
                            points = segment.points.map { it.toLatLng() },
                            color = color.copy(alpha = JUMP_ALPHA),
                            width = JUMP_WIDTH_PX,
                            pattern = listOf(Dash(DASH_PX), Gap(GAP_PX)),
                            startCap = ButtCap(),
                            endCap = ButtCap(),
                            zIndex = TRAIL_Z_INDEX,
                        )
                    }
                }
            }

            val direction = trail.direction
            if (settings.showDirection && direction != null) {
                Polyline(
                    points = listOf(direction.from.toLatLng(), LatLng(direction.toLatitude, direction.toLongitude)),
                    color = color,
                    width = DIRECTION_WIDTH_PX,
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    zIndex = TRAIL_Z_INDEX + 0.1f,
                )
            }
        }
    }

    if (settings.showDots) TrackerDots(trails = trails, nodeColors = nodeColors)
}

private data class TrackerDot(val nodeNum: Int, val point: TrailPoint, val level: Int)

@Composable
private fun TrackerDots(trails: List<NodeTrail>, nodeColors: Map<Int, Int>) {
    val density = LocalDensity.current.density
    val dots =
        remember(trails) {
            trails
                .flatMap { trail ->
                    val recency = trackRecency(trail.points.map { it.timeSeconds })
                    // The newest fix is under the node's chip already.
                    trail.points.dropLast(1).mapIndexed { index, point ->
                        TrackerDot(trail.nodeNum, point, trackFadeLevel(recency[index]))
                    }
                }
                .sortedByDescending { it.point.timeSeconds }
                .take(MAX_TRACKER_DOTS)
        }
    // One icon per node colour and fade level, built once, never per dot.
    val icons = remember(nodeColors, density) { mutableMapOf<Pair<Int, Int>, BitmapDescriptor>() }

    dots.forEach { dot ->
        key(dot.nodeNum, dot.point.timeSeconds) {
            val argb = nodeColors[dot.nodeNum] ?: FALLBACK_COLOR
            val icon =
                icons.getOrPut(argb to dot.level) { dotIcon(Color(argb), trackFadeLevelAlpha(dot.level), density) }
            Marker(
                state = rememberUpdatedMarkerState(position = dot.point.toLatLng()),
                icon = icon,
                anchor = Offset(DOT_ANCHOR, DOT_ANCHOR),
                flat = true,
                zIndex = TRAIL_Z_INDEX + 0.2f,
                onClick = { true },
            )
        }
    }
}

/** A filled dot with a white rim, drawn straight to a bitmap so building it never composes. */
private fun dotIcon(color: Color, alpha: Float, density: Float): BitmapDescriptor {
    val sizePx = (DOT_SIZE_DP * density).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(sizePx, sizePx)
    val radius = sizePx / 2f
    val rim = (DOT_RIM_DP * density).coerceAtLeast(1f)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.copy(alpha = alpha).toArgb() }
    val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = rim
            this.color = Color.White.copy(alpha = alpha).toArgb()
        }
    bitmap.applyCanvas {
        drawCircle(radius, radius, radius - rim, fill)
        drawCircle(radius, radius, radius - rim / 2f, stroke)
    }
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun TrailPoint.toLatLng() = LatLng(latitude, longitude)

/** Below the node chips and waypoints, above the basemap and imported layers. */
private const val TRAIL_Z_INDEX = 0.5f
private const val LINE_ALPHA = 0.85f
private const val LINE_WIDTH_PX = 8f
private const val JUMP_ALPHA = 0.6f
private const val JUMP_WIDTH_PX = 6f
private const val DASH_PX = 20f
private const val GAP_PX = 16f
private const val DIRECTION_WIDTH_PX = 6f
private const val DOT_SIZE_DP = 8f
private const val DOT_RIM_DP = 1f
private const val DOT_ANCHOR = 0.5f
private const val MAX_TRACKER_DOTS = 400

/** Slate, for a trail whose node is not in the database any more. */
private const val FALLBACK_COLOR = 0xFF607D8B.toInt()

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
package org.meshtastic.feature.map.maplibre.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.meshtastic.core.repository.TrackerSettings
import org.meshtastic.feature.map.maplibre.geojson.rememberFeatureSource
import org.meshtastic.feature.map.maplibre.geojson.toCssHex
import org.meshtastic.feature.map.trackFadeAlpha
import org.meshtastic.feature.map.trackRecency
import org.meshtastic.feature.map.tracker.NodeTrail
import org.meshtastic.feature.map.tracker.TrailPoint

private const val COLOR_KEY = "color"
private const val ALPHA_KEY = "alpha"

/**
 * The tracker's trails on the mesh map: a solid line where a node travelled, a dashed one where it jumped, a dot on
 * every fix and a short line ahead of a node that is moving.
 *
 * Each trail draws in its node's own colour, as the node's chip does, so a trail is recognisable without a label.
 * Declared before the node layers so the chips stay on top and every tap still reaches them.
 *
 * @param nodeColors Node number to the node's background colour, as packed ARGB.
 */
@Composable
internal fun TrackerLayers(trails: List<NodeTrail>, nodeColors: Map<Int, Int>, settings: TrackerSettings) {
    val lines = rememberFeatureSource(trails, nodeColors) { segmentFeatures(trails, nodeColors, jump = false) }
    LineLayer(
        id = "tracker-lines",
        source = lines,
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
        color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
        width = const(LINE_WIDTH_DP.dp),
        opacity = const(LINE_OPACITY),
    )

    if (settings.showJumps) {
        val jumps = rememberFeatureSource(trails, nodeColors) { segmentFeatures(trails, nodeColors, jump = true) }
        LineLayer(
            id = "tracker-jumps",
            source = jumps,
            cap = const(LineCap.Butt),
            color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
            width = const(JUMP_WIDTH_DP.dp),
            opacity = const(JUMP_OPACITY),
            // In line widths: two on, two off.
            dasharray = const(listOf<Number>(DASH, GAP)),
        )
    }

    if (settings.showDirection) {
        val directions = rememberFeatureSource(trails, nodeColors) { directionFeatures(trails, nodeColors) }
        LineLayer(
            id = "tracker-direction",
            source = directions,
            cap = const(LineCap.Round),
            color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
            width = const(DIRECTION_WIDTH_DP.dp),
        )
    }

    if (settings.showDots) {
        val dots = rememberFeatureSource(trails, nodeColors) { dotFeatures(trails, nodeColors) }
        CircleLayer(
            id = "tracker-dots",
            source = dots,
            color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
            radius = const(DOT_RADIUS_DP.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(DOT_STROKE_DP.dp),
            opacity = feature[ALPHA_KEY].asNumber(),
            strokeOpacity = feature[ALPHA_KEY].asNumber(),
        )
    }
}

internal fun segmentFeatures(
    trails: List<NodeTrail>,
    nodeColors: Map<Int, Int>,
    jump: Boolean,
): FeatureCollection<LineString, JsonObject?> = FeatureCollection(
    trails.flatMap { trail ->
        val color = trail.colorHex(nodeColors)
        trail.segments
            .filter { it.jump == jump && it.points.size > 1 }
            .map { segment ->
                Feature<LineString, JsonObject?>(
                    geometry = LineString(segment.points.map { it.toGeo() }),
                    properties = buildJsonObject { put(COLOR_KEY, color) },
                )
            }
    },
)

internal fun directionFeatures(
    trails: List<NodeTrail>,
    nodeColors: Map<Int, Int>,
): FeatureCollection<LineString, JsonObject?> = FeatureCollection(
    trails.mapNotNull { trail ->
        val direction = trail.direction ?: return@mapNotNull null
        Feature<LineString, JsonObject?>(
            geometry =
            LineString(
                listOf(
                    direction.from.toGeo(),
                    Position(longitude = direction.toLongitude, latitude = direction.toLatitude),
                ),
            ),
            properties = buildJsonObject { put(COLOR_KEY, trail.colorHex(nodeColors)) },
        )
    },
)

/**
 * Every fix but the newest, fading with age along its own trail. The newest is where the node's chip already sits, and
 * a dot under it would only show as a ring around the chip.
 */
internal fun dotFeatures(trails: List<NodeTrail>, nodeColors: Map<Int, Int>): FeatureCollection<Point, JsonObject?> =
    FeatureCollection(
        trails.flatMap { trail ->
            val color = trail.colorHex(nodeColors)
            val recency = trackRecency(trail.points.map { it.timeSeconds })
            trail.points.dropLast(1).mapIndexed { index, point ->
                Feature<Point, JsonObject?>(
                    geometry = Point(point.toGeo()),
                    properties =
                    buildJsonObject {
                        put(COLOR_KEY, color)
                        put(ALPHA_KEY, trackFadeAlpha(recency[index]))
                    },
                )
            }
        },
    )

private fun NodeTrail.colorHex(nodeColors: Map<Int, Int>): String = (nodeColors[nodeNum] ?: FALLBACK_COLOR).toCssHex()

private fun TrailPoint.toGeo() = Position(longitude = longitude, latitude = latitude)

private const val LINE_WIDTH_DP = 3
private const val LINE_OPACITY = 0.85f
private const val JUMP_WIDTH_DP = 2
private const val JUMP_OPACITY = 0.6f
private const val DASH = 2
private const val GAP = 2
private const val DIRECTION_WIDTH_DP = 2
private const val DOT_RADIUS_DP = 3
private const val DOT_STROKE_DP = 1

/** Slate, for a trail whose node is not in the database any more. */
private const val FALLBACK_COLOR = 0xFF607D8B.toInt()

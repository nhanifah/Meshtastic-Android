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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.map.maplibre

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.heatmapDensity
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.HeatmapLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapMode
import org.meshtastic.feature.map.maplibre.component.customRasterBasemaps
import org.meshtastic.feature.map.maplibre.component.rememberBasemapSelection
import org.meshtastic.feature.map.maplibre.geojson.MapChipKey
import org.meshtastic.feature.map.maplibre.geojson.NodeFeatureKeys
import org.meshtastic.feature.map.maplibre.geojson.circlePolygon
import org.meshtastic.feature.map.maplibre.geojson.featureValue
import org.meshtastic.feature.map.maplibre.geojson.rememberFeatureSource
import org.meshtastic.feature.map.maplibre.layers.MapChipLayer
import org.meshtastic.feature.map.maplibre.style.MapColors
import org.maplibre.spatialk.geojson.Position as GeoPosition

private const val CELL_ID_KEY = "cellId"
private const val COLOR_KEY = "color"
private const val ALPHA_KEY = "alpha"
private const val MISSED_KEY = "missed"
private const val WEIGHT_KEY = "w"
private const val ARGB_ALPHA_SHIFT = 24
private const val BYTE_MASK = 0xFF
private const val ALPHA_MAX = 255.0
private const val HEAT_OPACITY = 0.8f
private const val RING_OPACITY = 0.6f
private val RangeTestTrackColor = Color(0xFF1E88E5)
private val RangeTestRingColor = Color(0xFF546E7A)

/**
 * Range-test map on MapLibre (fdroid and desktop): signal grid, heatmap or beacon dots over the receiver's route, with
 * the stationary sender and distance rings. All bucketing and coloring happened in common code; this only draws.
 */
@Composable
fun MapLibreRangeTestMap(
    data: RangeTestMapData,
    onCellClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    customBasemaps: @Composable () -> List<org.meshtastic.feature.map.maplibre.style.Basemap.Raster> = {
        customRasterBasemaps()
    },
) {
    val basemaps = rememberBasemapSelection(customBasemaps()) ?: return
    val center =
        data.boundsPoints.firstOrNull()?.let { GeoPosition(longitude = it.longitude, latitude = it.latitude) }
            ?: GeoPosition(longitude = 0.0, latitude = 0.0)

    val mapState =
        rememberSecondaryMapState(basemaps, CameraPosition(center = center, zoom = DETAIL_ZOOM)) {
            RingLayer(data)
            when (data.mode) {
                RangeTestMapMode.GRID -> CellLayer(data, onCellClick)
                RangeTestMapMode.HEATMAP -> HeatLayer(data)
                RangeTestMapMode.POINTS -> Unit
            }
            TrackLayer(data)
            if (data.mode == RangeTestMapMode.POINTS) PointLayer(data)
            ReceiverLayer(data)
            TargetLayer(data)
        }

    // Frame once per session (keyed on the sender), not on every new sample — a live map that re-fits on each beacon
    // would fight the user's own panning.
    FitBoundsOnceVisible(mapState = mapState, key = data.target to data.boundsPoints.isNotEmpty()) {
        positionsBoundingBox(data.boundsPoints.map { GeoPosition(longitude = it.longitude, latitude = it.latitude) })
    }

    Box(modifier = modifier) {
        SecondaryMapSurface(mapState = mapState, basemaps = basemaps)
        SecondaryMapChrome(mapState = mapState, basemaps = basemaps)
    }
}

@Composable
private fun CellLayer(data: RangeTestMapData, onCellClick: (String) -> Unit) {
    val source =
        rememberFeatureSource(data.cells) {
            FeatureCollection(
                data.cells.map { cell ->
                    val ring =
                        listOf(
                            GeoPosition(longitude = cell.west, latitude = cell.south),
                            GeoPosition(longitude = cell.east, latitude = cell.south),
                            GeoPosition(longitude = cell.east, latitude = cell.north),
                            GeoPosition(longitude = cell.west, latitude = cell.north),
                            GeoPosition(longitude = cell.west, latitude = cell.south),
                        )
                    Feature<Polygon, JsonObject?>(
                        geometry = Polygon(listOf(ring)),
                        properties =
                        buildJsonObject {
                            put(CELL_ID_KEY, cell.id)
                            put(COLOR_KEY, cssColor(cell.argb))
                            put(ALPHA_KEY, alphaOf(cell.argb))
                        },
                    )
                },
            )
        }
    FillLayer(
        id = "rangetest-cells",
        source = source,
        color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
        opacity = feature[ALPHA_KEY].asNumber(const(1f)),
        outlineColor = const(Color.White),
        onClick = { features ->
            features.firstOrNull()?.properties?.get(CELL_ID_KEY)?.jsonPrimitive?.content?.let {
                onCellClick(it)
                ClickResult.Consume
            } ?: ClickResult.Pass
        },
    )
}

@Composable
private fun HeatLayer(data: RangeTestMapData) {
    val source =
        rememberFeatureSource(data.heat) {
            FeatureCollection(
                data.heat.map {
                    Feature<Point, JsonObject?>(
                        geometry = Point(GeoPosition(longitude = it.longitude, latitude = it.latitude)),
                        properties = buildJsonObject { put(WEIGHT_KEY, it.weight) },
                    )
                },
            )
        }
    HeatmapLayer(
        id = "rangetest-heat",
        source = source,
        weight = feature[WEIGHT_KEY].asNumber(const(0f)),
        radius = const(24.dp),
        opacity = const(HEAT_OPACITY),
        color =
        interpolate(
            linear(),
            heatmapDensity(),
            0f to const(Color.Transparent),
            0.2f to const(Color(0xFFFFF59D)),
            0.45f to const(Color(0xFFFBC02D)),
            0.7f to const(Color(0xFFF57C00)),
            1f to const(Color(0xFFD32F2F)),
        ),
    )
}

@Composable
private fun PointLayer(data: RangeTestMapData) {
    val source =
        rememberFeatureSource(data.points) {
            FeatureCollection(
                data.points.map {
                    Feature<Point, JsonObject?>(
                        geometry = Point(GeoPosition(longitude = it.longitude, latitude = it.latitude)),
                        properties =
                        buildJsonObject {
                            put(COLOR_KEY, cssColor(it.argb))
                            put(MISSED_KEY, it.missed)
                        },
                    )
                },
            )
        }
    CircleLayer(
        id = "rangetest-points",
        source = source,
        color = feature[COLOR_KEY].convertToColor(const(Color.Gray)),
        radius = const(5.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(1.dp),
        filter = !feature[MISSED_KEY].asBoolean(),
    )
    CircleLayer(
        id = "rangetest-missed",
        source = source,
        color = const(Color.Transparent),
        radius = const(5.dp),
        strokeColor = feature[COLOR_KEY].convertToColor(const(Color.DarkGray)),
        strokeWidth = const(2.dp),
        filter = feature[MISSED_KEY].asBoolean(),
    )
}

@Composable
private fun TrackLayer(data: RangeTestMapData) {
    if (data.track.size < 2) return
    val source =
        rememberFeatureSource(data.track) {
            FeatureCollection(
                listOf(
                    Feature<LineString, JsonObject?>(
                        geometry =
                        LineString(
                            data.track.map { GeoPosition(longitude = it.longitude, latitude = it.latitude) },
                        ),
                        properties = null,
                    ),
                ),
            )
        }
    LineLayer(
        id = "rangetest-track",
        source = source,
        color = const(RangeTestTrackColor),
        width = const(2.dp),
        cap = const(LineCap.Round),
    )
}

@Composable
private fun RingLayer(data: RangeTestMapData) {
    val target = data.target ?: return
    val source =
        rememberFeatureSource(target, data.ringsMeters) {
            FeatureCollection(
                data.ringsMeters.map { radius ->
                    Feature<Polygon, JsonObject?>(
                        geometry = circlePolygon(target.latitude, target.longitude, radius),
                        properties = null,
                    )
                },
            )
        }
    LineLayer(
        id = "rangetest-rings",
        source = source,
        color = const(RangeTestRingColor),
        width = const(1.dp),
        opacity = const(RING_OPACITY),
    )
}

@Composable
private fun ReceiverLayer(data: RangeTestMapData) {
    val receiver = data.receiver ?: return
    val source =
        rememberFeatureSource(receiver) {
            FeatureCollection(
                listOf(
                    Feature<Point, JsonObject?>(
                        geometry = Point(GeoPosition(longitude = receiver.longitude, latitude = receiver.latitude)),
                        properties = null,
                    ),
                ),
            )
        }
    CircleLayer(
        id = "rangetest-receiver",
        source = source,
        color = const(RangeTestTrackColor),
        radius = const(7.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
    )
}

@Composable
private fun TargetLayer(data: RangeTestMapData) {
    val target = data.target ?: return
    val chip =
        MapChipKey(
            label = target.label.take(TARGET_LABEL_MAX),
            background = MapColors.DiscoveryUser.toArgb(),
            foreground = Color.White.toArgb(),
            outlined = true,
        )
    val source =
        rememberFeatureSource(target, chip) {
            FeatureCollection(
                listOf(
                    Feature<Point, JsonObject?>(
                        geometry = Point(GeoPosition(longitude = target.longitude, latitude = target.latitude)),
                        properties = buildJsonObject { put(NodeFeatureKeys.CHIP, chip.featureValue()) },
                    ),
                ),
            )
        }
    MapChipLayer(id = "rangetest-target", source = source, chips = listOf(chip))
}

private const val TARGET_LABEL_MAX = 12

/** `#RRGGBB` for MapLibre's color parser; alpha travels separately as the layer opacity. */
private fun cssColor(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

private fun alphaOf(argb: Int): Double = ((argb ushr ARGB_ALPHA_SHIFT) and BYTE_MASK) / ALPHA_MAX

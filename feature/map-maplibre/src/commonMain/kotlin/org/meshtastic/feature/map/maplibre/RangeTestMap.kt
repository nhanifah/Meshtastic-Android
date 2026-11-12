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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.meshtastic.core.ui.util.RangeTestHeatRaster
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapPin
import org.meshtastic.feature.map.maplibre.component.customRasterBasemaps
import org.meshtastic.feature.map.maplibre.component.rememberBasemapSelection
import org.meshtastic.feature.map.maplibre.geojson.MapChipKey
import org.meshtastic.feature.map.maplibre.geojson.NodeFeatureKeys
import org.meshtastic.feature.map.maplibre.geojson.featureValue
import org.meshtastic.feature.map.maplibre.geojson.rememberFeatureSource
import org.meshtastic.feature.map.maplibre.layers.MapChipLayer
import org.meshtastic.feature.map.maplibre.style.MapColors
import org.maplibre.spatialk.geojson.Position as GeoPosition

private val RangeTestTrackColor = Color(0xFF1E88E5)
private val RangeTestReceiverColor = Color(0xFF1E88E5)
private const val TRACK_OPACITY = 0.7f
private const val PIN_LABEL_MAX = 12
private const val ALPHA_SHIFT = 24

/**
 * Range-test map on MapLibre (fdroid and desktop): the signal heatmap draped as an image (transparent wherever nothing
 * was measured), the receiver's route, and sender / receiver pins. No circles; all colouring happened in common code.
 */
@Composable
fun MapLibreRangeTestMap(
    data: RangeTestMapData,
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
            data.heat?.let { HeatLayer(it) }
            TrackLayer(data)
            data.sender?.let { PinLayer("rangetest-sender", it, MapColors.DiscoveryUser) }
            data.receiver?.let { PinLayer("rangetest-receiver", it, RangeTestReceiverColor) }
        }

    // Frame once per session (keyed on the sender), not on every new sample — a live map that re-fits on each beacon
    // would fight the user's own panning.
    FitBoundsOnceVisible(mapState = mapState, key = data.sender to data.boundsPoints.isNotEmpty()) {
        positionsBoundingBox(data.boundsPoints.map { GeoPosition(longitude = it.longitude, latitude = it.latitude) })
    }

    Box(modifier = modifier) {
        SecondaryMapSurface(mapState = mapState, basemaps = basemaps)
        SecondaryMapChrome(mapState = mapState, basemaps = basemaps)
    }
}

@Composable
private fun HeatLayer(heat: RangeTestHeatRaster) {
    // Rebuilt only when the heatmap changes; MapLibre resamples the small image linearly, so it reads as smooth.
    val image = remember(heat) { PreparedImage.fromBitmap(heat.toImageBitmap()) }
    val quad =
        remember(heat) {
            PositionQuad(
                topLeft = GeoPosition(longitude = heat.west, latitude = heat.north),
                topRight = GeoPosition(longitude = heat.east, latitude = heat.north),
                bottomRight = GeoPosition(longitude = heat.east, latitude = heat.south),
                bottomLeft = GeoPosition(longitude = heat.west, latitude = heat.south),
            )
        }
    val source = rememberImageSource(quad, image)
    RasterLayer(id = "rangetest-heat", source = source)
}

/** Paints the ARGB pixels one by one; the raster is small (≈160 px on its long side), so this is cheap. */
private fun RangeTestHeatRaster.toImageBitmap(): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = false }
    for (row in 0 until height) {
        for (col in 0 until width) {
            val argb = pixels[row * width + col]
            if (argb ushr ALPHA_SHIFT == 0) continue
            paint.color = Color(argb)
            canvas.drawRect(col.toFloat(), row.toFloat(), col + 1f, row + 1f, paint)
        }
    }
    return bitmap
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
        opacity = const(TRACK_OPACITY),
        cap = const(LineCap.Round),
    )
}

@Composable
private fun PinLayer(id: String, pin: RangeTestMapPin, color: Color) {
    val chip =
        MapChipKey(
            label = pin.label.take(PIN_LABEL_MAX),
            background = color.toArgb(),
            foreground = Color.White.toArgb(),
            outlined = true,
        )
    val source =
        rememberFeatureSource(pin, chip) {
            FeatureCollection(
                listOf(
                    Feature<Point, JsonObject?>(
                        geometry = Point(GeoPosition(longitude = pin.longitude, latitude = pin.latitude)),
                        properties = buildJsonObject { put(NodeFeatureKeys.CHIP, chip.featureValue()) },
                    ),
                ),
            )
        }
    MapChipLayer(id = id, source = source, chips = listOf(chip))
}

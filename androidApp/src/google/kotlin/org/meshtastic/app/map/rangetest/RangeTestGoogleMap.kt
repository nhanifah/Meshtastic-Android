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

package org.meshtastic.app.map.rangetest

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
import org.meshtastic.app.map.discovery.DiscoveryMarkerChip
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

private const val DEFAULT_ZOOM = 14f
private const val BOUNDS_PADDING_PX = 120
private const val HEAT_RADIUS_PX = 40
private const val HEAT_OPACITY = 0.8

/** Screen-space dot size for beacon points, converted to ground metres at the current zoom. */
private const val POINT_RADIUS_PX = 6.0
private const val RECEIVER_RADIUS_PX = 9.0
private const val EARTH_METERS_PER_PIXEL_Z0 = 156_543.03392

private val TargetColor = Color(0xFFFF9800)
private val TrackColor = Color(0xFF1E88E5)
private val RingColor = Color(0xFF546E7A).copy(alpha = 0.6f)

/** Weak = hot: transparent → yellow → orange → red. */
private val HeatGradient =
    Gradient(
        intArrayOf(0x00FFF59D, 0xFFFBC02D.toInt(), 0xFFF57C00.toInt(), 0xFFD32F2F.toInt()),
        floatArrayOf(0.05f, 0.4f, 0.7f, 1f),
    )

/**
 * Google Maps renderer for the range-test map: tappable grid cells, a weighted heatmap (android-maps-utils) or beacon
 * dots, the receiver's route, distance rings and the stationary sender.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun RangeTestGoogleMap(data: RangeTestMapData, onCellClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val start = data.boundsPoints.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, DEFAULT_ZOOM) }

    FitOnce(data, cameraState)

    val heatProvider =
        remember(data.heat) {
            data.heat
                .filter { it.weight > 0.0 }
                .takeIf { it.isNotEmpty() }
                ?.let { heat ->
                    HeatmapTileProvider.Builder()
                        .weightedData(heat.map { WeightedLatLng(LatLng(it.latitude, it.longitude), it.weight) })
                        .radius(HEAT_RADIUS_PX)
                        .gradient(HeatGradient)
                        .opacity(HEAT_OPACITY)
                        .build()
                }
        }

    val position = cameraState.position
    val metersPerPixel =
        EARTH_METERS_PER_PIXEL_Z0 * cos(position.target.latitude * PI / 180.0) / 2.0.pow(position.zoom.toDouble())

    GoogleMap(
        mapColorScheme = if (dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        modifier = modifier,
        uiSettings =
        MapUiSettings(
            zoomControlsEnabled = true,
            mapToolbarEnabled = false,
            compassEnabled = true,
            myLocationButtonEnabled = false,
        ),
        cameraPositionState = cameraState,
    ) {
        RangeTestOverlays(data, onCellClick, heatProvider, metersPerPixel)
    }
}

@Composable
private fun FitOnce(data: RangeTestMapData, cameraState: CameraPositionState) {
    // Frame once per sender (and once data first appears), not per beacon, so a live map does not fight panning.
    LaunchedEffect(data.target, data.boundsPoints.isNotEmpty()) {
        val points = data.boundsPoints.map { LatLng(it.latitude, it.longitude) }
        when {
            points.size >= 2 -> {
                val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
                cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX))
            }

            points.size == 1 -> cameraState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), DEFAULT_ZOOM))
        }
    }
}

@Composable
@GoogleMapComposable
private fun RangeTestOverlays(
    data: RangeTestMapData,
    onCellClick: (String) -> Unit,
    heatProvider: HeatmapTileProvider?,
    metersPerPixel: Double,
) {
    data.target?.let { target ->
        val center = LatLng(target.latitude, target.longitude)
        data.ringsMeters.forEach { radius ->
            Circle(center = center, radius = radius, strokeColor = RingColor, strokeWidth = 2f)
        }
    }

    if (data.mode == RangeTestMapMode.GRID) {
        data.cells.forEach { cell ->
            Polygon(
                points =
                listOf(
                    LatLng(cell.south, cell.west),
                    LatLng(cell.south, cell.east),
                    LatLng(cell.north, cell.east),
                    LatLng(cell.north, cell.west),
                ),
                fillColor = Color(cell.argb),
                strokeColor = Color.White.copy(alpha = 0.5f),
                strokeWidth = 1f,
                clickable = true,
                onClick = { onCellClick(cell.id) },
            )
        }
    }

    if (data.mode == RangeTestMapMode.HEATMAP && heatProvider != null) {
        TileOverlay(tileProvider = heatProvider)
    }
    RouteAndMarkers(data, metersPerPixel)
}

@Composable
@GoogleMapComposable
private fun RouteAndMarkers(data: RangeTestMapData, metersPerPixel: Double) {
    if (data.track.size >= 2) {
        Polyline(points = data.track.map { LatLng(it.latitude, it.longitude) }, color = TrackColor, width = 6f)
    }

    if (data.mode == RangeTestMapMode.POINTS) {
        data.points.forEach { point ->
            Circle(
                center = LatLng(point.latitude, point.longitude),
                radius = POINT_RADIUS_PX * metersPerPixel,
                fillColor = if (point.missed) Color.Transparent else Color(point.argb),
                strokeColor = if (point.missed) Color(point.argb) else Color.White,
                strokeWidth = if (point.missed) 4f else 2f,
            )
        }
    }

    data.receiver?.let { receiver ->
        Circle(
            center = LatLng(receiver.latitude, receiver.longitude),
            radius = RECEIVER_RADIUS_PX * metersPerPixel,
            fillColor = TrackColor,
            strokeColor = Color.White,
            strokeWidth = 4f,
        )
    }

    data.target?.let { target ->
        MarkerComposable(
            state = rememberUpdatedMarkerState(position = LatLng(target.latitude, target.longitude)),
            title = target.label,
        ) {
            DiscoveryMarkerChip(label = target.label, color = TargetColor)
        }
    }
}

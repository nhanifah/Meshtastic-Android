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

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import org.meshtastic.app.map.discovery.DiscoveryMarkerChip
import org.meshtastic.core.ui.util.RangeTestHeatRaster
import org.meshtastic.core.ui.util.RangeTestMapData

private const val DEFAULT_ZOOM = 14f
private const val BOUNDS_PADDING_PX = 120

private val SenderColor = Color(0xFFFF9800)
private val ReceiverColor = Color(0xFF1E88E5)
private val TrackColor = Color(0xFF1E88E5).copy(alpha = 0.7f)

/**
 * Google Maps renderer for the range-test map: the signal heatmap draped as a ground overlay (already transparent where
 * nothing was measured), the receiver's route, and sender / receiver pins. No circles.
 */
@Composable
fun RangeTestGoogleMap(data: RangeTestMapData, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val start = data.boundsPoints.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, DEFAULT_ZOOM) }

    FitOnce(data, cameraState)

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
        data.heat?.let { HeatOverlay(it) }
        RouteAndPins(data)
    }
}

@Composable
private fun FitOnce(data: RangeTestMapData, cameraState: CameraPositionState) {
    // Frame once per sender (and once data first appears), not per beacon, so a live map does not fight panning.
    LaunchedEffect(data.sender, data.boundsPoints.isNotEmpty()) {
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
private fun HeatOverlay(heat: RangeTestHeatRaster) {
    // Built only when the heatmap itself changes; the map filters the small image smoothly as it scales.
    val image =
        remember(heat) {
            val bitmap = Bitmap.createBitmap(heat.pixels, heat.width, heat.height, Bitmap.Config.ARGB_8888)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    val bounds = remember(heat) { LatLngBounds(LatLng(heat.south, heat.west), LatLng(heat.north, heat.east)) }
    GroundOverlay(position = GroundOverlayPosition.create(bounds), image = image)
}

@Composable
@GoogleMapComposable
private fun RouteAndPins(data: RangeTestMapData) {
    if (data.track.size >= 2) {
        Polyline(points = data.track.map { LatLng(it.latitude, it.longitude) }, color = TrackColor, width = 5f)
    }
    data.sender?.let { sender ->
        MarkerComposable(
            sender.label,
            state = rememberUpdatedMarkerState(position = LatLng(sender.latitude, sender.longitude)),
            title = sender.label,
        ) {
            DiscoveryMarkerChip(label = sender.label, color = SenderColor)
        }
    }
    data.receiver?.let { receiver ->
        MarkerComposable(
            receiver.label,
            state = rememberUpdatedMarkerState(position = LatLng(receiver.latitude, receiver.longitude)),
            title = receiver.label,
        ) {
            DiscoveryMarkerChip(label = receiver.label, color = ReceiverColor)
        }
    }
}

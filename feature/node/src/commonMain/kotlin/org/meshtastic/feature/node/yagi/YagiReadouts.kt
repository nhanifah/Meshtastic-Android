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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.model.util.toDistanceString
import org.meshtastic.core.model.util.toElevationString
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.yagi_alt_barometer
import org.meshtastic.core.resources.yagi_alt_manual
import org.meshtastic.core.resources.yagi_alt_phone_ellipsoid
import org.meshtastic.core.resources.yagi_alt_phone_msl
import org.meshtastic.core.resources.yagi_alt_radio
import org.meshtastic.core.resources.yagi_azimuth
import org.meshtastic.core.resources.yagi_azimuth_magnetic
import org.meshtastic.core.resources.yagi_back_azimuth
import org.meshtastic.core.resources.yagi_boom_heading
import org.meshtastic.core.resources.yagi_boom_tilt
import org.meshtastic.core.resources.yagi_declination
import org.meshtastic.core.resources.yagi_distance
import org.meshtastic.core.resources.yagi_earth_bulge
import org.meshtastic.core.resources.yagi_elevation
import org.meshtastic.core.resources.yagi_far_end
import org.meshtastic.core.resources.yagi_no_own_altitude
import org.meshtastic.core.resources.yagi_no_target_altitude
import org.meshtastic.core.resources.yagi_return_elevation
import org.meshtastic.core.resources.yagi_signal
import org.meshtastic.core.resources.yagi_signal_value
import org.meshtastic.core.resources.yagi_target_alt_manual
import org.meshtastic.core.ui.util.formatAgo
import kotlin.math.roundToInt

@Composable
internal fun ReadoutCard(state: YagiPointingUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Readout(stringResource(Res.string.yagi_azimuth), state.azimuthTrue?.let { deg(it, 1) })
            Readout(
                stringResource(Res.string.yagi_azimuth_magnetic),
                state.azimuthMagnetic?.let { deg(it, 1) },
                state.declination?.let { stringResource(Res.string.yagi_declination, deg(it, 1)) },
            )
            Readout(stringResource(Res.string.yagi_boom_heading), state.heading?.let { deg(it, 0) })
            HorizontalDivider()
            Readout(
                stringResource(Res.string.yagi_elevation),
                state.targetElevation?.let { signedDeg(it) },
                elevationNote(state),
            )
            Readout(stringResource(Res.string.yagi_boom_tilt), state.boomElevation?.let { signedDeg(it) })
            HorizontalDivider()
            Readout(
                stringResource(Res.string.yagi_distance),
                state.distanceM?.roundToInt()?.toDistanceString(state.displayUnits),
            )
            Readout(
                stringResource(Res.string.yagi_signal),
                if (state.snr != null || state.rssi != null) {
                    stringResource(
                        Res.string.yagi_signal_value,
                        state.snr?.let { NumberFormatter.format(it, 1) } ?: "–",
                        state.rssi?.toString() ?: "–",
                    )
                } else {
                    null
                },
                state.lastHeard.takeIf { it > 0 }?.let { formatAgo(it) },
            )
        }
    }
}

@Composable
private fun elevationNote(state: YagiPointingUiState): String? = when {
    !state.hasTargetAltitude && state.hasTargetPosition -> stringResource(Res.string.yagi_no_target_altitude)

    else ->
        listOfNotNull(
            ownAltitudeNote(state.ownAltitudeSource),
            state.manual.targetAltitudeM?.let { stringResource(Res.string.yagi_target_alt_manual) },
        )
            .joinToString("\n")
}

@Composable
private fun ownAltitudeNote(source: AltitudeSource): String = when (source) {
    AltitudeSource.MANUAL -> stringResource(Res.string.yagi_alt_manual)
    AltitudeSource.PHONE_MSL -> stringResource(Res.string.yagi_alt_phone_msl)
    AltitudeSource.RADIO_GPS -> stringResource(Res.string.yagi_alt_radio)
    AltitudeSource.PHONE_ELLIPSOID -> stringResource(Res.string.yagi_alt_phone_ellipsoid)
    AltitudeSource.BAROMETER -> stringResource(Res.string.yagi_alt_barometer)
    AltitudeSource.NONE -> stringResource(Res.string.yagi_no_own_altitude)
}

@Composable
internal fun FarEndCard(state: YagiPointingUiState) {
    if (state.backAzimuth == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.yagi_far_end), style = MaterialTheme.typography.titleMedium)
            Readout(stringResource(Res.string.yagi_back_azimuth), deg(state.backAzimuth, 1))
            Readout(stringResource(Res.string.yagi_return_elevation), state.returnElevation?.let { signedDeg(it) })
            Readout(
                stringResource(Res.string.yagi_earth_bulge),
                state.midpointBulgeM?.roundToInt()?.toElevationString(state.displayUnits),
            )
        }
    }
}

@Composable
internal fun Readout(label: String, value: String?, note: String? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun deg(value: Double, decimals: Int): String = NumberFormatter.format(value, decimals) + "°"

private fun signedDeg(value: Double): String = (if (value > 0) "+" else "") + deg(value, 1)

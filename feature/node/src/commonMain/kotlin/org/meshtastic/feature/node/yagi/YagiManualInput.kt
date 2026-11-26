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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.yagi_auto_value
import org.meshtastic.core.resources.yagi_heading_by_hand
import org.meshtastic.core.resources.yagi_heights
import org.meshtastic.core.resources.yagi_manual_heading
import org.meshtastic.core.resources.yagi_manual_hint
import org.meshtastic.core.resources.yagi_manual_inputs
import org.meshtastic.core.resources.yagi_manual_latitude
import org.meshtastic.core.resources.yagi_manual_longitude
import org.meshtastic.core.resources.yagi_manual_own_altitude
import org.meshtastic.core.resources.yagi_manual_target_altitude
import org.meshtastic.core.resources.yagi_manual_tilt
import org.meshtastic.core.resources.yagi_no_compass_hint
import org.meshtastic.core.resources.yagi_no_tilt_hint
import org.meshtastic.core.resources.yagi_own_mast
import org.meshtastic.core.resources.yagi_sensor_accelerometer
import org.meshtastic.core.resources.yagi_sensor_barometer
import org.meshtastic.core.resources.yagi_sensor_gps
import org.meshtastic.core.resources.yagi_sensor_gyroscope
import org.meshtastic.core.resources.yagi_sensor_magnetometer
import org.meshtastic.core.resources.yagi_sensor_missing
import org.meshtastic.core.resources.yagi_sensor_present
import org.meshtastic.core.resources.yagi_sensors
import org.meshtastic.core.resources.yagi_target_mast
import org.meshtastic.core.resources.yagi_tilt_by_hand

private const val COORDINATE_DECIMALS = 6

/**
 * Which sensors the phone has. Heading and tilt follow the sensors when they exist; a switch hands either to manual
 * entry, for a phone whose compass is thrown off by the mast's metal.
 */
@Composable
internal fun SensorStatusCard(state: YagiPointingUiState, actions: YagiInputActions) {
    val s = state.sensors
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.yagi_sensors), style = MaterialTheme.typography.titleMedium)
            SensorRow(stringResource(Res.string.yagi_sensor_magnetometer), s.magnetometer)
            SensorRow(stringResource(Res.string.yagi_sensor_accelerometer), s.accelerometer)
            SensorRow(stringResource(Res.string.yagi_sensor_gyroscope), s.gyroscope)
            SensorRow(stringResource(Res.string.yagi_sensor_barometer), s.barometer)
            SensorRow(stringResource(Res.string.yagi_sensor_gps), s.gps)
            if (s.canSenseHeading) {
                SwitchRow(stringResource(Res.string.yagi_heading_by_hand), state.manual.headingManual) {
                    actions.setHeadingManual(it)
                }
            } else {
                Hint(stringResource(Res.string.yagi_no_compass_hint))
            }
            if (s.canSenseTilt) {
                SwitchRow(stringResource(Res.string.yagi_tilt_by_hand), state.manual.tiltManual) {
                    actions.setTiltManual(it)
                }
            } else {
                Hint(stringResource(Res.string.yagi_no_tilt_hint))
            }
        }
    }
}

/** Every value the phone may not measure, typed by hand. A blank field goes back to the sensors. */
@Composable
internal fun ManualInputCard(state: YagiPointingUiState, actions: YagiInputActions) {
    val m = state.manual
    val autoAltitude = state.ownAltitudeM?.takeIf { state.ownAltitudeSource != AltitudeSource.MANUAL }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.yagi_manual_inputs), style = MaterialTheme.typography.titleMedium)
            Hint(stringResource(Res.string.yagi_manual_hint))
            if (state.needsManualHeading) {
                NumberField(
                    stringResource(Res.string.yagi_manual_heading),
                    m.headingMagnetic,
                    actions::setManualHeading,
                )
            }
            if (state.needsManualTilt) {
                NumberField(stringResource(Res.string.yagi_manual_tilt), m.tilt, actions::setManualTilt)
            }
            NumberField(
                label = stringResource(Res.string.yagi_manual_own_altitude),
                value = m.ownAltitudeM,
                onChange = actions::setOwnAltitude,
                auto = autoAltitude?.let { NumberFormatter.format(it, 0) },
            )
            NumberField(
                label = stringResource(Res.string.yagi_manual_target_altitude),
                value = m.targetAltitudeM,
                onChange = actions::setTargetAltitude,
                auto = state.reportedTargetAltitudeM?.let { NumberFormatter.format(it, 0) },
            )
            if (state.ownPositionSource != OwnPositionSource.PHONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        stringResource(Res.string.yagi_manual_latitude),
                        m.ownLatitude,
                        actions::setOwnLatitude,
                        Modifier.weight(1f),
                    )
                    NumberField(
                        stringResource(Res.string.yagi_manual_longitude),
                        m.ownLongitude,
                        actions::setOwnLongitude,
                        Modifier.weight(1f),
                    )
                }
            }
            Text(stringResource(Res.string.yagi_heights), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    stringResource(Res.string.yagi_own_mast),
                    m.ownMastM.takeIf { it > 0 },
                    actions::setOwnMastHeight,
                    Modifier.weight(1f),
                )
                NumberField(
                    stringResource(Res.string.yagi_target_mast),
                    m.targetMastM.takeIf { it > 0 },
                    actions::setTargetMastHeight,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SensorRow(label: String, present: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (present) Res.string.yagi_sensor_present else Res.string.yagi_sensor_missing),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (present) OnTargetColor else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Signed decimal entry. Blank or unparsable text reports null, which hands the value back to the sensors. */
@Composable
private fun NumberField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    auto: String? = null,
) {
    var text by rememberSaveable { mutableStateOf(value?.let(::plain) ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned =
                input.replace(',', '.').filterIndexed { i, c -> c.isDigit() || c == '.' || (c == '-' && i == 0) }
            text = cleaned
            onChange(cleaned.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        placeholder = auto?.let { { Text(stringResource(Res.string.yagi_auto_value, it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun plain(value: Double): String =
    NumberFormatter.format(value, COORDINATE_DECIMALS).trimEnd('0').trimEnd('.', ',')

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
package org.meshtastic.feature.rangetest.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.model.util.toDistanceString
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_hops

internal fun formatDistance(meters: Double, units: MeasurementSystem): String = meters.toInt().toDistanceString(units)

internal fun formatSnr(snr: Float): String = "${NumberFormatter.format(snr, 1)} dB"

internal fun formatRssi(rssi: Number): String = "${NumberFormatter.format(rssi.toFloat(), 0)} dBm"

/** "SNR -4.5 dB · -112 dBm · Hops 0", leaving out whatever the packet did not report. */
@Composable
internal fun signalText(snr: Float?, rssi: Int?, hops: Int?): String = listOfNotNull(
    snr?.let(::formatSnr),
    rssi?.let(::formatRssi),
    hops?.let { stringResource(Res.string.range_test_hops, it) },
)
    .joinToString(" · ")

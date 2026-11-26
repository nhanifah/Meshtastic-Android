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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val MAX_MAST_HEIGHT_M = 500.0
private const val MAX_TILT_DEG = 90.0
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** Holds what the user typed on the Yagi screen, clamped to values that make sense. */
class YagiManualInputsStore : YagiInputActions {
    private val manual = MutableStateFlow(YagiManualInputs())
    val values: StateFlow<YagiManualInputs> = manual.asStateFlow()

    override fun setOwnMastHeight(meters: Double?) = manual.update {
        it.copy(ownMastM = (meters ?: 0.0).coerceIn(0.0, MAX_MAST_HEIGHT_M))
    }

    override fun setTargetMastHeight(meters: Double?) = manual.update {
        it.copy(targetMastM = (meters ?: 0.0).coerceIn(0.0, MAX_MAST_HEIGHT_M))
    }

    override fun setHeadingManual(enabled: Boolean) = manual.update { it.copy(headingManual = enabled) }

    override fun setManualHeading(magneticDegrees: Double?) = manual.update {
        it.copy(headingMagnetic = magneticDegrees?.let(YagiGeometry::normalize))
    }

    override fun setTiltManual(enabled: Boolean) = manual.update { it.copy(tiltManual = enabled) }

    override fun setManualTilt(degrees: Double?) = manual.update {
        it.copy(tilt = degrees?.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))
    }

    override fun setOwnAltitude(meters: Double?) = manual.update { it.copy(ownAltitudeM = meters) }

    override fun setTargetAltitude(meters: Double?) = manual.update { it.copy(targetAltitudeM = meters) }

    override fun setOwnLatitude(degrees: Double?) = manual.update {
        it.copy(ownLatitude = degrees?.takeIf { d -> d in -MAX_LATITUDE..MAX_LATITUDE })
    }

    override fun setOwnLongitude(degrees: Double?) = manual.update {
        it.copy(ownLongitude = degrees?.takeIf { d -> d in -MAX_LONGITUDE..MAX_LONGITUDE })
    }
}

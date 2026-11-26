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

import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.feature.node.compass.CompassWarning
import kotlin.math.abs

/** Within this many degrees of azimuth the beam counts as on target. */
internal const val AZIMUTH_TOLERANCE_DEG = 3.0

/** Within this many degrees of elevation the boom counts as on target. */
internal const val ELEVATION_TOLERANCE_DEG = 2.0

/** Where the height of this end of the link came from, best first. */
enum class AltitudeSource {
    MANUAL,
    PHONE_MSL,
    RADIO_GPS,
    PHONE_ELLIPSOID,
    BAROMETER,
    NONE,
}

/** Where the position of this end of the link came from, best first. */
enum class OwnPositionSource {
    MANUAL,
    PHONE,
    RADIO,
    NONE,
}

/** Whether a reading comes from a phone sensor, from what the user typed, or is missing. */
enum class InputSource {
    SENSOR,
    MANUAL,
    NONE,
}

/**
 * Everything the user typed. A null field means "measure it"; the heading and tilt are used only while their manual
 * switch is on (forced on when the phone lacks the sensor).
 */
data class YagiManualInputs(
    val ownMastM: Double = 0.0,
    val targetMastM: Double = 0.0,
    val headingManual: Boolean = false,
    /** Boom heading read off a hand compass, magnetic degrees. */
    val headingMagnetic: Double? = null,
    val tiltManual: Boolean = false,
    val tilt: Double? = null,
    val ownAltitudeM: Double? = null,
    val ownLatitude: Double? = null,
    val ownLongitude: Double? = null,
    val targetAltitudeM: Double? = null,
)

data class YagiPointingUiState(
    val targetName: String = "",
    val hasTargetPosition: Boolean = true,
    val hasTargetAltitude: Boolean = false,
    val warnings: List<CompassWarning> = emptyList(),
    val displayUnits: MeasurementSystem = MeasurementSystem.METRIC,
    val sensors: PhoneSensors = PhoneSensors(),
    val manual: YagiManualInputs = YagiManualInputs(),
    val headingSource: InputSource = InputSource.NONE,
    val tiltSource: InputSource = InputSource.NONE,
    /** Where the boom points now, true north. */
    val heading: Double? = null,
    /** How far the boom's front is raised above the horizon now. */
    val boomElevation: Double? = null,
    val azimuthTrue: Double? = null,
    val azimuthMagnetic: Double? = null,
    val declination: Double? = null,
    val backAzimuth: Double? = null,
    val distanceM: Double? = null,
    val targetElevation: Double? = null,
    /** The tilt the far end needs to point back here. */
    val returnElevation: Double? = null,
    val midpointBulgeM: Double? = null,
    /** Signed turn left (negative) or right (positive) to bring the beam on target. */
    val turnBy: Double? = null,
    /** Signed tilt down (negative) or up (positive) to bring the beam on target. */
    val tiltBy: Double? = null,
    val ownAltitudeM: Double? = null,
    val ownAltitudeSource: AltitudeSource = AltitudeSource.NONE,
    val ownPositionSource: OwnPositionSource = OwnPositionSource.NONE,
    val targetAltitudeM: Double? = null,
    /** The altitude the target node reported itself, before any manual override. */
    val reportedTargetAltitudeM: Double? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val lastHeard: Int = 0,
) {
    val isAzimuthAligned: Boolean
        get() = turnBy?.let { abs(it) <= AZIMUTH_TOLERANCE_DEG } == true

    val isElevationAligned: Boolean
        get() = tiltBy?.let { abs(it) <= ELEVATION_TOLERANCE_DEG } == true

    val isOnTarget: Boolean
        get() = isAzimuthAligned && (tiltBy == null || isElevationAligned)

    /** Heading must be typed: no compass, or the user switched to manual. */
    val needsManualHeading: Boolean
        get() = manual.headingManual || !sensors.canSenseHeading

    val needsManualTilt: Boolean
        get() = manual.tiltManual || !sensors.canSenseTilt
}

/** What the Yagi screen's switches and fields can change. A null value clears the field back to "measure it". */
interface YagiInputActions {
    fun setOwnMastHeight(meters: Double?)

    fun setTargetMastHeight(meters: Double?)

    fun setHeadingManual(enabled: Boolean)

    fun setManualHeading(magneticDegrees: Double?)

    fun setTiltManual(enabled: Boolean)

    fun setManualTilt(degrees: Double?)

    fun setOwnAltitude(meters: Double?)

    fun setTargetAltitude(meters: Double?)

    fun setOwnLatitude(degrees: Double?)

    fun setOwnLongitude(degrees: Double?)
}

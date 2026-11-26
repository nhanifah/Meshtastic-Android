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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.LocaleUnitsProvider
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.common.util.bearing
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.repository.MagneticFieldProvider
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PhoneLocationProvider
import org.meshtastic.core.repository.PhoneLocationState
import org.meshtastic.feature.node.compass.CompassHeadingProvider
import org.meshtastic.feature.node.compass.CompassWarning
import org.meshtastic.feature.node.compass.HeadingState

/** Weight of each new compass sample: low enough to calm magnetometer jitter, high enough to follow a turning boom. */
private const val HEADING_SMOOTHING = 0.2
private const val PITCH_SMOOTHING = 0.25
private const val STOP_TIMEOUT_MS = 5_000L

private data class Nodes(val target: Node?, val own: Node?)

private data class Environment(val location: PhoneLocationState, val pressureHpa: Float?)

private data class OwnFix(val position: Pair<Double, Double>?, val source: OwnPositionSource)

/**
 * Drives the Yagi pointing screen: azimuth, elevation and turn/tilt guidance from the phone's compass, its tilt, its
 * GPS and barometer, and the target node's last reported position. Whatever the phone cannot sense, the user types in.
 * Sensors run only while the screen is collecting.
 */
@KoinViewModel
class YagiPointingViewModel(
    @InjectedParam val destNum: Int,
    nodeRepository: NodeRepository,
    headingProvider: CompassHeadingProvider,
    phoneLocationProvider: PhoneLocationProvider,
    sensorsProvider: PhoneSensorsProvider,
    private val magneticFieldProvider: MagneticFieldProvider,
    localeUnitsProvider: LocaleUnitsProvider,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val sensors = sensorsProvider.sensors()

    /** The screen's switches and fields write here; the state below recomputes on every change. */
    val inputs = YagiManualInputsStore()
    private val manual: StateFlow<YagiManualInputs> = inputs.values

    private val nodes: Flow<Nodes> =
        combine(nodeRepository.nodeDBbyNum, nodeRepository.ourNodeInfo) { db, own -> Nodes(db[destNum], own) }
            .distinctUntilChanged()

    private val smoothedHeading: Flow<HeadingState> =
        headingProvider
            .headingUpdates()
            .scan(null as HeadingState?) { previous, next ->
                val heading =
                    next.heading?.let { h ->
                        YagiGeometry.smoothAngle(previous?.heading?.toDouble(), h.toDouble(), HEADING_SMOOTHING)
                            .toFloat()
                    }
                val pitch =
                    next.pitch?.let { p ->
                        val last = previous?.pitch
                        if (last == null) p else (last + PITCH_SMOOTHING * (p - last)).toFloat()
                    }
                next.copy(heading = heading, pitch = pitch)
            }
            .map { it ?: HeadingState(hasSensor = sensors.canSenseHeading) }

    private val environment: Flow<Environment> =
        combine(
            phoneLocationProvider.locationUpdates().onStart {
                emit(PhoneLocationState(permissionGranted = true, providerEnabled = true))
            },
            sensorsProvider.pressureUpdates().map<Float, Float?> { it }.onStart { emit(null) },
        ) { location, pressure ->
            Environment(location, pressure)
        }

    val uiState: StateFlow<YagiPointingUiState> =
        combine(nodes, smoothedHeading, environment, localeUnitsProvider.measurementSystem, manual) {
                nodes,
                heading,
                env,
                units,
                manual,
            ->
            buildState(nodes, heading, env, units, manual)
        }
            .flowOn(dispatchers.default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                YagiPointingUiState(sensors = sensors),
            )

    private fun ownFix(manual: YagiManualInputs, location: PhoneLocationState, own: Node?): OwnFix {
        val phone = location.location?.takeIf { location.permissionGranted }
        val manualLat = manual.ownLatitude
        val manualLon = manual.ownLongitude
        return when {
            manualLat != null && manualLon != null -> OwnFix(manualLat to manualLon, OwnPositionSource.MANUAL)
            phone != null -> OwnFix(phone.latitude to phone.longitude, OwnPositionSource.PHONE)
            own?.validPosition != null -> OwnFix(own.latitude to own.longitude, OwnPositionSource.RADIO)
            else -> OwnFix(null, OwnPositionSource.NONE)
        }
    }

    private fun ownAltitude(manual: YagiManualInputs, env: Environment, own: Node?): Pair<Double?, AltitudeSource> {
        val phone = env.location.location?.takeIf { env.location.permissionGranted }
        val radioAlt = own?.validPosition?.altitude?.toDouble()
        return when {
            manual.ownAltitudeM != null -> manual.ownAltitudeM to AltitudeSource.MANUAL

            phone?.mslAltitude != null -> phone.mslAltitude to AltitudeSource.PHONE_MSL

            radioAlt != null -> radioAlt to AltitudeSource.RADIO_GPS

            phone?.altitude != null -> phone.altitude to AltitudeSource.PHONE_ELLIPSOID

            env.pressureHpa != null ->
                YagiGeometry.pressureAltitudeMeters(env.pressureHpa.toDouble()) to AltitudeSource.BAROMETER

            else -> null to AltitudeSource.NONE
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildState(
        nodes: Nodes,
        headingState: HeadingState,
        env: Environment,
        units: MeasurementSystem,
        manual: YagiManualInputs,
    ): YagiPointingUiState {
        val target = nodes.target
        val targetPos = target?.validPosition?.let { target.latitude to target.longitude }
        val fix = ownFix(manual, env.location, nodes.own)
        val ownPos = fix.position
        val (ownAlt, ownAltSource) = ownAltitude(manual, env, nodes.own)
        val reportedTargetAlt = target?.validPosition?.altitude?.toDouble()
        val targetAlt = manual.targetAltitudeM ?: reportedTargetAlt

        val declination = ownPos?.let { (lat, lon) ->
            magneticFieldProvider.getDeclination(lat, lon, ownAlt ?: 0.0, nowMillis).toDouble()
        }
        val manualHeading = manual.headingManual || !sensors.canSenseHeading
        val magneticHeading = if (manualHeading) manual.headingMagnetic else headingState.heading?.toDouble()
        val heading = magneticHeading?.let { YagiGeometry.normalize(it + (declination ?: 0.0)) }
        val manualTilt = manual.tiltManual || !sensors.canSenseTilt
        val boom = if (manualTilt) manual.tilt else headingState.pitch?.toDouble()

        val azimuth = if (ownPos != null && targetPos != null) bearing(ownPos, targetPos) else null
        val distance = if (ownPos != null && targetPos != null) distance(ownPos, targetPos) else null

        val fromHeight = ownAlt?.plus(manual.ownMastM)
        val toHeight = targetAlt?.plus(manual.targetMastM)
        val elevation = elevation(fromHeight, toHeight, distance)
        val returnElevation = elevation(toHeight, fromHeight, distance)

        return YagiPointingUiState(
            targetName =
            target?.let { n -> n.effectiveLongName.ifBlank { n.user.short_name.ifBlank { "${n.num}" } } } ?: "",
            hasTargetPosition = targetPos != null,
            hasTargetAltitude = targetAlt != null,
            warnings = buildWarnings(env.location, fix.source),
            displayUnits = units,
            sensors = sensors,
            manual = manual,
            headingSource = source(manualHeading, heading),
            tiltSource = source(manualTilt, boom),
            heading = heading,
            boomElevation = boom,
            azimuthTrue = azimuth,
            azimuthMagnetic = azimuth?.let { YagiGeometry.normalize(it - (declination ?: 0.0)) },
            declination = declination,
            backAzimuth = azimuth?.let(YagiGeometry::backAzimuth),
            distanceM = distance,
            targetElevation = elevation,
            returnElevation = returnElevation,
            midpointBulgeM = distance?.let { YagiGeometry.midpointBulgeMeters(it) },
            turnBy = if (heading != null && azimuth != null) YagiGeometry.signedDelta(heading, azimuth) else null,
            tiltBy = if (boom != null && elevation != null) elevation - boom else null,
            ownAltitudeM = ownAlt,
            ownAltitudeSource = ownAltSource,
            ownPositionSource = fix.source,
            targetAltitudeM = targetAlt,
            reportedTargetAltitudeM = reportedTargetAlt,
            snr = target?.snrOrNull,
            rssi = target?.rssiOrNull,
            lastHeard = target?.lastHeard ?: 0,
        )
    }

    private fun source(manual: Boolean, value: Double?): InputSource = when {
        value == null -> InputSource.NONE
        manual -> InputSource.MANUAL
        else -> InputSource.SENSOR
    }

    private fun elevation(from: Double?, to: Double?, distance: Double?): Double? =
        if (from != null && to != null && distance != null) YagiGeometry.elevationDegrees(from, to, distance) else null

    private fun bearing(from: Pair<Double, Double>, to: Pair<Double, Double>): Double =
        bearing(from.first, from.second, to.first, to.second)

    private fun distance(from: Pair<Double, Double>, to: Pair<Double, Double>): Double =
        latLongToMeter(from.first, from.second, to.first, to.second)

    /** Location warnings matter only when nothing else (typed or the radio's GPS) gives this end a position. */
    private fun buildWarnings(location: PhoneLocationState, ownPosSource: OwnPositionSource): List<CompassWarning> =
        buildList {
            if (ownPosSource == OwnPositionSource.PHONE || ownPosSource == OwnPositionSource.MANUAL) return@buildList
            when {
                !location.permissionGranted -> add(CompassWarning.NO_LOCATION_PERMISSION)
                !location.providerEnabled -> add(CompassWarning.LOCATION_DISABLED)
                else -> add(CompassWarning.NO_LOCATION_FIX)
            }
        }
}

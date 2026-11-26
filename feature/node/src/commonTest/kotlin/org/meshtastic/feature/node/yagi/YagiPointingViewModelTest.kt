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

import app.cash.turbine.test
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.MagneticFieldProvider
import org.meshtastic.core.repository.PhoneLocation
import org.meshtastic.core.repository.PhoneLocationProvider
import org.meshtastic.core.repository.PhoneLocationState
import org.meshtastic.core.testing.FakeLocaleUnitsProvider
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.feature.node.compass.CompassHeadingProvider
import org.meshtastic.feature.node.compass.CompassWarning
import org.meshtastic.feature.node.compass.HeadingState
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class YagiPointingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = CoroutineDispatchers(main = testDispatcher, io = testDispatcher, default = testDispatcher)

    private val nodeRepository = FakeNodeRepository()
    private val headingProvider: CompassHeadingProvider = mock()
    private val phoneLocationProvider: PhoneLocationProvider = mock()
    private val magneticFieldProvider: MagneticFieldProvider = mock()
    private val pressureFlow = MutableStateFlow<Float?>(null)
    private var sensors = PhoneSensors(magnetometer = true, accelerometer = true, gyroscope = true, gps = true)
    private val sensorsProvider =
        object : PhoneSensorsProvider {
            override fun sensors(): PhoneSensors = sensors

            override fun pressureUpdates(): Flow<Float> = pressureFlow.filterNotNull()
        }

    private val headingFlow = MutableStateFlow(HeadingState())
    private val locationFlow = MutableStateFlow(PhoneLocationState(permissionGranted = true, providerEnabled = true))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { headingProvider.headingUpdates() } returns headingFlow
        every { phoneLocationProvider.locationUpdates() } returns locationFlow
        every { magneticFieldProvider.getDeclination(any(), any(), any(), any()) } returns 0f
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = YagiPointingViewModel(
        destNum = TARGET,
        nodeRepository = nodeRepository,
        headingProvider = headingProvider,
        phoneLocationProvider = phoneLocationProvider,
        sensorsProvider = sensorsProvider,
        magneticFieldProvider = magneticFieldProvider,
        localeUnitsProvider = FakeLocaleUnitsProvider(),
        dispatchers = dispatchers,
    )

    private fun node(num: Int, lat: Double, lon: Double, alt: Int?) = Node(
        num = num,
        user = user("!$num", "Node $num"),
        position =
        Position.Builder()
            .also { wb ->
                wb.latitude_i = (lat * 1e7).toInt()
                wb.longitude_i = (lon * 1e7).toInt()
                wb.altitude = alt
            }
            .build(),
    )

    private fun user(id: String, name: String) = User.Builder()
        .also { wb ->
            wb.id = id
            wb.long_name = name
        }
        .build()

    private fun phoneAt(lat: Double, lon: Double, msl: Double?) {
        locationFlow.value =
            PhoneLocationState(
                permissionGranted = true,
                providerEnabled = true,
                location = PhoneLocation(lat, lon, altitude = msl, timeMillis = 1L, mslAltitude = msl),
            )
    }

    @Test
    fun target_due_east_and_higher_gives_turn_and_tilt() = runTest {
        // Target ~1.1 km due east, 100 m higher.
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 200)))
        phoneAt(1.0, 1.0, 100.0)
        headingFlow.value = HeadingState(heading = 0f, pitch = 0f)

        viewModel().uiState.test {
            val s = expectMostRecentItem()
            assertEquals("Node $TARGET", s.targetName)
            assertEquals(90.0, s.azimuthTrue!!, 0.1)
            assertEquals(270.0, s.backAzimuth!!, 0.1)
            assertEquals(90.0, s.turnBy!!, 0.1)
            assertTrue(s.targetElevation!! > 5.0 && s.targetElevation!! < 5.3)
            assertTrue(s.tiltBy!! > 5.0)
            assertEquals(AltitudeSource.PHONE_MSL, s.ownAltitudeSource)
            assertFalse(s.isOnTarget)
            assertTrue(s.warnings.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pointing_at_target_is_on_target() = runTest {
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        phoneAt(1.0, 1.0, 100.0)
        headingFlow.value = HeadingState(heading = 90f, pitch = 0f)

        viewModel().uiState.test {
            val s = expectMostRecentItem()
            assertTrue(s.isAzimuthAligned)
            assertTrue(s.isElevationAligned)
            assertTrue(s.isOnTarget)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mast_heights_raise_the_ends() = runTest {
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        phoneAt(1.0, 1.0, 100.0)
        val vm = viewModel()

        vm.uiState.test {
            val level = expectMostRecentItem().targetElevation!!
            vm.inputs.setTargetMastHeight(50.0)
            val raised = expectMostRecentItem()
            assertEquals(50.0, raised.manual.targetMastM)
            assertTrue(raised.targetElevation!! > level + 2.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun falls_back_to_radio_position_without_phone_fix() = runTest {
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        nodeRepository.setOurNode(node(OWN, 1.0, 1.0, 80))
        locationFlow.value = PhoneLocationState(permissionGranted = false, providerEnabled = true)

        viewModel().uiState.test {
            val s = expectMostRecentItem()
            assertEquals(OwnPositionSource.RADIO, s.ownPositionSource)
            assertEquals(AltitudeSource.RADIO_GPS, s.ownAltitudeSource)
            assertNotNull(s.azimuthTrue)
            assertTrue(CompassWarning.NO_LOCATION_PERMISSION in s.warnings)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun target_without_position_has_no_guidance() = runTest {
        nodeRepository.setNodes(listOf(Node(num = TARGET, user = user("!t", "T"))))
        phoneAt(1.0, 1.0, 100.0)
        headingFlow.value = HeadingState(heading = 10f, pitch = 0f)

        viewModel().uiState.test {
            val s = expectMostRecentItem()
            assertFalse(s.hasTargetPosition)
            assertNull(s.azimuthTrue)
            assertNull(s.turnBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun phone_without_compass_takes_the_typed_heading() = runTest {
        sensors = PhoneSensors(accelerometer = true, gps = true)
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        phoneAt(1.0, 1.0, 100.0)
        headingFlow.value = HeadingState(hasSensor = false, pitch = 0f)
        val vm = viewModel()

        vm.uiState.test {
            val before = expectMostRecentItem()
            assertTrue(before.needsManualHeading)
            assertEquals(InputSource.NONE, before.headingSource)
            assertNull(before.turnBy)
            assertEquals(InputSource.SENSOR, before.tiltSource)

            vm.inputs.setManualHeading(80.0)
            val after = expectMostRecentItem()
            assertEquals(InputSource.MANUAL, after.headingSource)
            assertEquals(10.0, after.turnBy!!, 0.1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun manual_switch_overrides_a_working_compass() = runTest {
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        phoneAt(1.0, 1.0, 100.0)
        headingFlow.value = HeadingState(heading = 0f, pitch = 0f)
        val vm = viewModel()

        vm.uiState.test {
            vm.inputs.setHeadingManual(true)
            vm.inputs.setManualHeading(90.0)
            vm.inputs.setTiltManual(true)
            vm.inputs.setManualTilt(0.0)
            val s = expectMostRecentItem()
            assertEquals(InputSource.MANUAL, s.headingSource)
            assertEquals(InputSource.MANUAL, s.tiltSource)
            assertTrue(s.isOnTarget)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun barometer_is_the_last_altitude_resort() = runTest {
        sensors = sensors.copy(barometer = true)
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, 100)))
        locationFlow.value =
            PhoneLocationState(
                permissionGranted = true,
                providerEnabled = true,
                location = PhoneLocation(1.0, 1.0, altitude = null, timeMillis = 1L),
            )
        pressureFlow.value = 1013.25f

        viewModel().uiState.test {
            val s = expectMostRecentItem()
            assertEquals(AltitudeSource.BAROMETER, s.ownAltitudeSource)
            assertEquals(0.0, s.ownAltitudeM!!, 0.5)
            assertNotNull(s.targetElevation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun typed_position_and_altitudes_win() = runTest {
        // The node reports no altitude, and the phone has no permission: everything comes from the fields.
        nodeRepository.setNodes(listOf(node(TARGET, 1.0, 1.01, null)))
        locationFlow.value = PhoneLocationState(permissionGranted = false, providerEnabled = true)
        val vm = viewModel()

        vm.uiState.test {
            assertNull(expectMostRecentItem().targetElevation)
            vm.inputs.setOwnLatitude(1.0)
            vm.inputs.setOwnLongitude(1.0)
            vm.inputs.setOwnAltitude(0.0)
            vm.inputs.setTargetAltitude(100.0)
            val s = expectMostRecentItem()
            assertEquals(OwnPositionSource.MANUAL, s.ownPositionSource)
            assertEquals(AltitudeSource.MANUAL, s.ownAltitudeSource)
            assertEquals(90.0, s.azimuthTrue!!, 0.1)
            assertTrue(s.targetElevation!! > 5.0)
            assertTrue(s.warnings.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val TARGET = 42
        const val OWN = 7
    }
}

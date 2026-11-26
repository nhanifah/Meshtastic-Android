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

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.annotation.Single

@Single
class AndroidPhoneSensorsProvider(private val context: Context) : PhoneSensorsProvider {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun sensors(): PhoneSensors {
        fun has(type: Int) = sensorManager?.getDefaultSensor(type) != null
        return PhoneSensors(
            magnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
            accelerometer = has(Sensor.TYPE_ACCELEROMETER),
            gyroscope = has(Sensor.TYPE_GYROSCOPE),
            barometer = has(Sensor.TYPE_PRESSURE),
            gps = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
        )
    }

    override fun pressureUpdates(): Flow<Float> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(event.values[0])
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { manager.unregisterListener(listener) }
    }
}

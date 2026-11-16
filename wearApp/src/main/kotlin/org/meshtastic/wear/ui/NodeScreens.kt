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
package org.meshtastic.wear.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.compass_no_location_fix
import org.meshtastic.core.resources.nodes
import org.meshtastic.core.resources.wear_no_nodes
import org.meshtastic.core.resources.wear_no_position
import org.meshtastic.core.resources.wear_rotate_to_calibrate

@Composable
fun NodesScreen(onOpen: (Int) -> Unit) {
    val viewModel: NodesViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ListScreen(title = stringResource(Res.string.nodes)) {
        if (state.nodes.isEmpty()) item { InfoText(stringResource(Res.string.wear_no_nodes)) }
        state.nodes.forEach { node ->
            item(key = node.num) {
                RowButton(
                    label = node.effectiveLongName.ifBlank { node.user.short_name },
                    secondary = nodeSummary(node, state.here, state.units),
                    selected = node.isFavorite,
                    onClick = { onOpen(node.num) },
                )
            }
        }
    }
}

/** "5m · SNR 6.5 · 1.2 km · 87%" — whatever is known. */
fun nodeSummary(node: Node, here: Here?, units: MeasurementSystem): String = buildList {
    if (node.lastHeard > 0) add(agoShort(node.lastHeard * MILLIS_PER_SECOND))
    if (node.snr != Float.MAX_VALUE) add("SNR ${oneDecimal(node.snr)}")
    distanceTo(here, node)?.let { add(formatDistance(it, units == MeasurementSystem.IMPERIAL)) }
    node.batteryLevel?.takeIf { it in 1..MAX_BATTERY }?.let { add("$it%") }
}
    .joinToString(" · ")

@Composable
fun NodeDetailScreen(nodeNum: Int, modifier: Modifier = Modifier) {
    val viewModel: NodeDetailViewModel = koinViewModel(key = "node-$nodeNum") { parametersOf(nodeNum) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val node = state.node
    val heading = rememberHeading(state.here)
    val bearing = node?.let { bearingTo(state.here, it) }
    val distance = node?.let { distanceTo(state.here, it) }

    ScreenScaffold(modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bearing != null) CompassArrow(bearing = bearing, heading = heading)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    node?.effectiveLongName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                if (distance != null) {
                    Text(
                        formatDistance(distance, state.units == MeasurementSystem.IMPERIAL),
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
                val hint =
                    when {
                        node == null -> ""
                        node.validPosition == null -> stringResource(Res.string.wear_no_position)
                        state.here == null -> stringResource(Res.string.compass_no_location_fix)
                        heading == null -> stringResource(Res.string.wear_rotate_to_calibrate)
                        else -> nodeSummary(node, null, state.units)
                    }
                Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** An arrow round the bezel pointing at the node; up is where the top of the watch points. */
@Composable
private fun CompassArrow(bearing: Double, heading: Float?) {
    val color = MaterialTheme.colorScheme.primary
    val angle = (bearing - (heading ?: 0f)).toFloat()
    Canvas(Modifier.fillMaxSize()) {
        rotate(angle) {
            val tip = Offset(size.width / 2, ARROW_INSET)
            val path =
                Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - ARROW_HALF_WIDTH, tip.y + ARROW_LENGTH)
                    lineTo(tip.x + ARROW_HALF_WIDTH, tip.y + ARROW_LENGTH)
                    close()
                }
            drawPath(path, color = if (heading == null) color.copy(alpha = 0.4f) else color)
        }
    }
}

/**
 * Degrees clockwise from true north that the top of the watch points to, from the rotation-vector sensor, or null while
 * the watch has no such sensor.
 */
@Composable
fun rememberHeading(here: Here?): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val declination =
        here?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
        } ?: 0f
    DisposableEffect(declination) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotation = FloatArray(ROTATION_MATRIX_SIZE)
        val orientation = FloatArray(ORIENTATION_SIZE)
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    val magnetic = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    heading = (magnetic + declination + FULL_TURN) % FULL_TURN
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
        sensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { manager.unregisterListener(listener) }
    }
    return heading
}

private const val MILLIS_PER_SECOND = 1000L
private const val MAX_BATTERY = 100
private const val ARROW_INSET = 6f
private const val ARROW_HALF_WIDTH = 18f
private const val ARROW_LENGTH = 30f
private const val ROTATION_MATRIX_SIZE = 9
private const val ORIENTATION_SIZE = 3
private const val FULL_TURN = 360f

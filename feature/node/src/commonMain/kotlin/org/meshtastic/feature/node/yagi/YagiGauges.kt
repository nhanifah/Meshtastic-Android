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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val OnTargetColor = Color(0xFF2E7D32)
internal val TargetMarkerColor = Color(0xFFFF6D00)
private const val GAUGE_RANGE_DEG = 45f
private const val DEG_PER_HALF_TURN = 180f

/**
 * Compass rose that turns with the boom: the fixed line at the top is where the antenna points, the orange marker is
 * the target, and the arc between them is the turn still to make.
 */
@Composable
@Suppress("LongMethod", "MagicNumber")
internal fun YagiDial(heading: Double?, azimuth: Double?, onTarget: Boolean, modifier: Modifier = Modifier) {
    val ringColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointerColor = if (onTarget) OnTargetColor else MaterialTheme.colorScheme.secondary
    val textMeasurer = rememberTextMeasurer()
    val cardinalStyle = remember { TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    val degreeStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium) }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * 0.9f
        val h = (heading ?: 0.0).toFloat()

        // The turn still to make, drawn in screen space from the boom (top) toward the target.
        if (heading != null && azimuth != null) {
            val turn = YagiGeometry.signedDelta(heading, azimuth).toFloat()
            drawArc(
                color = (if (onTarget) OnTargetColor else TargetMarkerColor).copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = turn,
                useCenter = true,
                topLeft = Offset(center.x - radius * 0.8f, center.y - radius * 0.8f),
                size = androidx.compose.ui.geometry.Size(radius * 1.6f, radius * 1.6f),
            )
        }

        rotate(-h, center) {
            drawCircle(ringColor, radius, center, style = Stroke(3.dp.toPx()))
            for (d in 0 until 360 step 5) {
                val len =
                    when {
                        d % 90 == 0 -> radius * 0.14f
                        d % 30 == 0 -> radius * 0.09f
                        else -> radius * 0.045f
                    }
                drawLine(
                    color = if (d == 0) Color.Red else tickColor,
                    start = polar(center, radius - len, d.toFloat()),
                    end = polar(center, radius, d.toFloat()),
                    strokeWidth = (if (d % 30 == 0) 2.dp else 1.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (label, d) ->
                val p = polar(center, radius * 0.66f, d.toFloat())
                val layout =
                    textMeasurer.measure(label, cardinalStyle.copy(color = if (d == 0) Color.Red else ringColor))
                withTransform({ rotate(h, p) }) {
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            for (d in 30 until 360 step 30) {
                if (d % 90 == 0) continue
                val p = polar(center, radius * 0.78f, d.toFloat())
                val layout = textMeasurer.measure(d.toString(), degreeStyle.copy(color = labelColor))
                withTransform({ rotate(h, p) }) {
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            if (azimuth != null) drawTargetMarker(center, radius, azimuth.toFloat())
        }

        // The boom: fixed, always straight up the screen.
        if (heading != null) {
            drawLine(
                color = pointerColor,
                start = center,
                end = Offset(center.x, center.y - radius * 0.85f),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(pointerColor, 7.dp.toPx(), center)
        }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawTargetMarker(center: Offset, radius: Float, bearing: Float) {
    val tip = polar(center, radius * 0.86f, bearing)
    val left = polar(center, radius * 1.02f, bearing - 5f)
    val right = polar(center, radius * 1.02f, bearing + 5f)
    val path =
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
    drawPath(path, TargetMarkerColor)
}

/** Side-on inclinometer: the needle is the boom's tilt, the orange tick the elevation the target needs, ±45° shown. */
@Composable
@Suppress("MagicNumber")
internal fun TiltGauge(boom: Double?, target: Double?, onTarget: Boolean, modifier: Modifier = Modifier) {
    val scaleColor = MaterialTheme.colorScheme.outline
    val horizonColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = if (onTarget) OnTargetColor else MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        val pivot = Offset(size.width * 0.15f, size.height / 2f)
        val radius =
            minOf(size.width * 0.8f, size.height / 2f / sin(GAUGE_RANGE_DEG * PI.toFloat() / DEG_PER_HALF_TURN) * 0.95f)

        // Scale: ticks every 5°, longer every 15°. Angle here is above the horizon, so screen y flips.
        for (d in -45..45 step 5) {
            val len = if (d % 15 == 0) radius * 0.12f else radius * 0.06f
            drawLine(
                color = if (d == 0) horizonColor else scaleColor,
                start = tiltPoint(pivot, radius - len, d.toFloat()),
                end = tiltPoint(pivot, radius, d.toFloat()),
                strokeWidth = (if (d == 0) 2.dp else 1.dp).toPx(),
            )
        }
        drawLine(horizonColor.copy(alpha = 0.4f), pivot, tiltPoint(pivot, radius, 0f), strokeWidth = 1.dp.toPx())

        if (target != null) {
            val t = target.toFloat().coerceIn(-GAUGE_RANGE_DEG, GAUGE_RANGE_DEG)
            drawLine(
                color = TargetMarkerColor,
                start = tiltPoint(pivot, radius * 0.8f, t),
                end = tiltPoint(pivot, radius * 1.04f, t),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (boom != null) {
            val b = boom.toFloat().coerceIn(-GAUGE_RANGE_DEG, GAUGE_RANGE_DEG)
            drawLine(
                color = needleColor,
                start = pivot,
                end = tiltPoint(pivot, radius * 0.95f, b),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(needleColor, 6.dp.toPx(), pivot)
        }
    }
}

/** Point [r] from [c] along compass bearing [deg] (0 = up, clockwise). */
private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / DEG_PER_HALF_TURN
    return Offset(c.x + r * sin(a), c.y - r * cos(a))
}

/** Point [r] from [c] at [deg] above the horizon, pointing right. */
private fun tiltPoint(c: Offset, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / DEG_PER_HALF_TURN
    return Offset(c.x + r * cos(a), c.y - r * sin(a))
}

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
package org.meshtastic.feature.rangetest

import org.meshtastic.core.ui.util.RangeTestHeatRaster
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One measurement for the heatmap: [strength] on the 0–1 scale of [RangeTestSignal.strength], or null if missed. */
data class RangeTestHeatInput(val latitude: Double, val longitude: Double, val strength: Float?)

/**
 * Turns scattered beacon readings into a smooth, semi-transparent signal-strength image.
 *
 * Each pixel blends the readings around it (Gaussian weights within an influence radius sized from how far apart the
 * readings are), so the colour shows the local signal level rather than how many readings landed there. Missed beacons
 * pull the colour toward grey. Pixels farther than the radius from every reading stay fully transparent, so the base
 * map shows through everywhere the test did not go.
 */
object RangeTestHeatmap {
    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val HALF_TURN_DEG = 180.0
    private const val MIN_COS = 1e-6
    private const val LONG_SIDE_PX = 160
    private const val MIN_SIDE_PX = 8
    private const val SPACING_FACTOR = 2.5
    private const val MIN_RADIUS_M = 40.0
    private const val MAX_RADIUS_M = 500.0
    private const val SPACING_SAMPLE_LIMIT = 600
    private const val SIGMA_FRACTION = 0.5
    private const val MAX_ALPHA = 165
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
    private const val MISSED_GREY = 0xFF9E9E9E.toInt()

    /** Strength stops at each band's centre, very weak → strong, matching [SignalBand]. */
    private val STOPS = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
    private val STOP_COLORS =
        intArrayOf(
            SignalBand.VERY_WEAK.argb,
            SignalBand.WEAK.argb,
            SignalBand.FAIR.argb,
            SignalBand.GOOD.argb,
            SignalBand.STRONG.argb,
        )

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun render(inputs: List<RangeTestHeatInput>, version: Long): RangeTestHeatRaster? {
        if (inputs.isEmpty()) return null
        val lat0 = inputs.sumOf { it.latitude } / inputs.size
        val mPerLon = (METERS_PER_DEGREE_LAT * cos(lat0 * PI / HALF_TURN_DEG)).coerceAtLeast(MIN_COS)
        val lon0 = inputs.sumOf { it.longitude } / inputs.size
        val xs = DoubleArray(inputs.size) { (inputs[it].longitude - lon0) * mPerLon }
        val ys = DoubleArray(inputs.size) { (inputs[it].latitude - lat0) * METERS_PER_DEGREE_LAT }

        val radius = influenceRadius(xs, ys)
        val sigma2 = (radius * SIGMA_FRACTION).let { it * it }
        val minX = xs.min() - radius
        val maxX = xs.max() + radius
        val minY = ys.min() - radius
        val maxY = ys.max() + radius
        val spanX = maxX - minX
        val spanY = maxY - minY
        val pxSize = max(spanX, spanY) / LONG_SIDE_PX
        val width = (spanX / pxSize).roundToInt().coerceAtLeast(MIN_SIDE_PX)
        val height = (spanY / pxSize).roundToInt().coerceAtLeast(MIN_SIDE_PX)

        // Bucket readings by radius-sized cells so each pixel only looks at its 3×3 neighbourhood.
        val buckets = HashMap<Long, MutableList<Int>>()
        for (i in inputs.indices) {
            buckets.getOrPut(key(cell(xs[i], radius), cell(ys[i], radius))) { mutableListOf() }.add(i)
        }

        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val y = maxY - (row + HALF) * (spanY / height)
            val cy = cell(y, radius)
            for (col in 0 until width) {
                val x = minX + (col + HALF) * (spanX / width)
                val cx = cell(x, radius)
                var weightAll = 0.0
                var weightMissed = 0.0
                var weightSignal = 0.0
                var strengthSum = 0.0
                var nearest = Double.MAX_VALUE
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val members = buckets[key(cx + dx, cy + dy)] ?: continue
                        for (i in members) {
                            val ddx = xs[i] - x
                            val ddy = ys[i] - y
                            val d2 = ddx * ddx + ddy * ddy
                            if (d2 > radius * radius) continue
                            nearest = minOf(nearest, d2)
                            val w = exp(-d2 / (2 * sigma2))
                            weightAll += w
                            val s = inputs[i].strength
                            if (s == null) {
                                weightMissed += w
                            } else {
                                weightSignal += w
                                strengthSum += w * s
                            }
                        }
                    }
                }
                if (weightAll <= 0.0) continue
                val coverage = 1.0 - sqrt(nearest) / radius
                val alpha = (MAX_ALPHA * smoothStep(coverage)).roundToInt()
                if (alpha <= 0) continue
                val loss = (weightMissed / weightAll).toFloat()
                val base = if (weightSignal > 0) colorAt((strengthSum / weightSignal).toFloat()) else MISSED_GREY
                pixels[row * width + col] = withAlpha(lerpColor(base, MISSED_GREY, loss), alpha)
            }
        }

        return RangeTestHeatRaster(
            width = width,
            height = height,
            pixels = pixels,
            north = lat0 + maxY / METERS_PER_DEGREE_LAT,
            south = lat0 + minY / METERS_PER_DEGREE_LAT,
            east = lon0 + maxX / mPerLon,
            west = lon0 + minX / mPerLon,
            version = version,
        )
    }

    /** The colour of a 0–1 strength, blended between the band colours. */
    @Suppress("ReturnCount")
    fun colorAt(strength: Float): Int {
        val s = strength.coerceIn(0f, 1f)
        if (s <= STOPS.first()) return STOP_COLORS.first()
        if (s >= STOPS.last()) return STOP_COLORS.last()
        val i = STOPS.indexOfFirst { it > s } - 1
        val t = (s - STOPS[i]) / (STOPS[i + 1] - STOPS[i])
        return lerpColor(STOP_COLORS[i], STOP_COLORS[i + 1], t)
    }

    /**
     * Twice the typical distance between neighbouring readings, bounded: wide enough that a walked route reads as one
     * continuous band, narrow enough that it does not smear across blocks nobody visited.
     */
    @Suppress("ReturnCount")
    private fun influenceRadius(xs: DoubleArray, ys: DoubleArray): Double {
        if (xs.size < 2) return MIN_RADIUS_M * 2
        val step = max(1, xs.size / SPACING_SAMPLE_LIMIT)
        val nearest = ArrayList<Double>()
        for (i in xs.indices step step) {
            var best = Double.MAX_VALUE
            for (j in xs.indices) {
                if (i == j) continue
                val dx = xs[i] - xs[j]
                val dy = ys[i] - ys[j]
                val d2 = dx * dx + dy * dy
                if (d2 > 0 && d2 < best) best = d2
            }
            if (best < Double.MAX_VALUE) nearest.add(sqrt(best))
        }
        if (nearest.isEmpty()) return MIN_RADIUS_M * 2
        nearest.sort()
        return (nearest[nearest.size / 2] * SPACING_FACTOR).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    }

    private fun cell(v: Double, size: Double): Int = floor(v / size).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl Int.SIZE_BITS) or (cy.toLong() and LOW_32_BITS)

    private fun smoothStep(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (SMOOTH_A - SMOOTH_B * c)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (from shr shift) and BYTE_MASK
            val b = (to shr shift) and BYTE_MASK
            return (a + (b - a) * k).roundToInt() and BYTE_MASK
        }
        return (ch(RED_SHIFT) shl RED_SHIFT) or (ch(GREEN_SHIFT) shl GREEN_SHIFT) or ch(0)
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int = (alpha shl ALPHA_SHIFT) or (rgb and RGB_MASK)

    private const val HALF = 0.5
    private const val LOW_32_BITS = 0xFFFFFFFFL
    private const val SMOOTH_A = 3.0
    private const val SMOOTH_B = 2.0
    private const val RGB_MASK = 0x00FFFFFF
}

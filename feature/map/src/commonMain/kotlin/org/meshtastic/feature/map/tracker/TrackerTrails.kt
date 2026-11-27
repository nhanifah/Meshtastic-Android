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
package org.meshtastic.feature.map.tracker

import org.meshtastic.core.common.util.EARTH_RADIUS_METERS
import org.meshtastic.core.common.util.bearing
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.model.util.GeoConstants
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One fix on a trail: where the node was, and when (epoch seconds). */
data class TrailPoint(val latitude: Double, val longitude: Double, val timeSeconds: Long)

/**
 * A stretch of trail drawn as one line. [jump] marks a leg the node did not visibly travel — it went quiet for too long
 * or would have had to move impossibly fast — which is drawn dashed, the way an APRS tracker draws a station that
 * jumps, so a gap in coverage does not read as a road taken.
 */
data class TrailSegment(val points: List<TrailPoint>, val jump: Boolean)

/** The line drawn ahead of a moving node, from its newest fix towards where it is heading. */
data class DirectionLine(val from: TrailPoint, val toLatitude: Double, val toLongitude: Double, val headingDeg: Double)

/** Everything the tracker draws for one node. */
data class NodeTrail(
    val nodeNum: Int,
    /** Every fix inside the tail, oldest first, after stationary runs are merged: the dots. */
    val points: List<TrailPoint>,
    val segments: List<TrailSegment>,
    /** Present only while the node is moving and was heard recently enough for its heading to still mean something. */
    val direction: DirectionLine?,
    /** Metres per second over the newest travelled leg, or the node's own reported speed when it sent one. */
    val speedMetersPerSecond: Double?,
)

/**
 * The tracker's rules, kept together so both map engines draw the same trail for the same reasons.
 *
 * The defaults follow how APRS trackers (aprs.to among them) treat a station's history, adapted to a mesh, where fixes
 * arrive minutes apart rather than seconds.
 */
data class TrackerRules(
    /** Fixes within this distance of a run's first fix collapse into one, so GPS jitter at a stop is not a scribble. */
    val stationaryRadiusMeters: Double = STATIONARY_RADIUS_METERS,
    /** A silence longer than this between two fixes makes the leg a jump. */
    val jumpGapSeconds: Long = JUMP_GAP_SECONDS,
    /** A leg faster than this (about 300 km/h, beyond any ground vehicle) is a jump, usually a bad fix. */
    val jumpSpeedMetersPerSecond: Double = JUMP_SPEED_MPS,
    /** Slower than this, a node is parked, however its fixes wander. */
    val movingSpeedMetersPerSecond: Double = MOVING_SPEED_MPS,
    /** A node not heard for this long gets no direction line: its heading is history by now. */
    val directionMaxAgeSeconds: Long = DIRECTION_MAX_AGE_SECONDS,
    /** The direction line reaches as far as the node would travel in this many seconds... */
    val directionLookaheadSeconds: Double = DIRECTION_LOOKAHEAD_SECONDS,
    /** ...but never shorter or longer than these, so it stays visible on foot and sane on a motorway. */
    val directionMinMeters: Double = DIRECTION_MIN_METERS,
    val directionMaxMeters: Double = DIRECTION_MAX_METERS,
    /** Newest fixes kept per node; the rest are dropped so one chatty node cannot cost the whole map. */
    val maxPointsPerNode: Int = MAX_POINTS_PER_NODE,
    /** A fix this far in the future is a node with a wrong clock, not a fix to draw. */
    val futureSkewSeconds: Long = FUTURE_SKEW_SECONDS,
) {
    companion object {
        const val STATIONARY_RADIUS_METERS = 25.0
        const val JUMP_GAP_SECONDS = 30 * 60L
        const val JUMP_SPEED_MPS = 83.0
        const val MOVING_SPEED_MPS = 0.5
        const val DIRECTION_MAX_AGE_SECONDS = 15 * 60L
        const val DIRECTION_LOOKAHEAD_SECONDS = 120.0
        const val DIRECTION_MIN_METERS = 40.0
        const val DIRECTION_MAX_METERS = 2_000.0
        const val MAX_POINTS_PER_NODE = 500
        const val FUTURE_SKEW_SECONDS = 5 * 60L
    }
}

/** A raw fix as read from the position log, before the tracker's rules have touched it. */
data class TrackerFix(
    val latitude: Double,
    val longitude: Double,
    val timeSeconds: Long,
    /** km/h, as the protobuf carries it; null when the node did not send one. */
    val groundSpeedKmh: Int? = null,
    /** Degrees true, already scaled out of the protobuf's 1e-5 units; null when the node did not send one. */
    val groundTrackDeg: Double? = null,
)

/**
 * Builds [nodeNum]'s trail from its [fixes] (any order) for a tail of [tailSeconds] ending at [nowSeconds]. Returns
 * null when nothing inside the tail is drawable.
 */
@Suppress("ReturnCount")
fun buildNodeTrail(
    nodeNum: Int,
    fixes: List<TrackerFix>,
    nowSeconds: Long,
    tailSeconds: Long,
    rules: TrackerRules = TrackerRules(),
): NodeTrail? {
    val cutoff = nowSeconds - tailSeconds
    val inTail =
        fixes
            .filter {
                it.isDrawable() && it.timeSeconds >= cutoff && it.timeSeconds <= nowSeconds + rules.futureSkewSeconds
            }
            .sortedBy { it.timeSeconds }
            .distinctBy { it.timeSeconds }
            .takeLast(rules.maxPointsPerNode)
    if (inTail.isEmpty()) return null

    val merged = mergeStationary(inTail, rules.stationaryRadiusMeters)
    val points = merged.map { TrailPoint(it.latitude, it.longitude, it.timeSeconds) }
    val segments = segment(points, rules)
    val newest = merged.last()
    val speed = currentSpeed(merged, rules)
    val direction = directionLine(newest, merged, speed, nowSeconds, rules)
    return NodeTrail(
        nodeNum = nodeNum,
        points = points,
        segments = segments,
        direction = direction,
        speedMetersPerSecond = speed,
    )
}

/** Whether a leg between [a] and [b] is a jump under [rules]. */
fun isJump(a: TrailPoint, b: TrailPoint, rules: TrackerRules = TrackerRules()): Boolean {
    val dt = b.timeSeconds - a.timeSeconds
    if (dt > rules.jumpGapSeconds) return true
    val distance = latLongToMeter(a.latitude, a.longitude, b.latitude, b.longitude)
    // Two fixes in the same second, far apart, are as impossible as a fast leg.
    return if (dt <= 0) distance > rules.stationaryRadiusMeters else distance / dt > rules.jumpSpeedMetersPerSecond
}

/**
 * The point [distanceMeters] from ([latitude], [longitude]) along [bearingDeg], on the same sphere as [latLongToMeter].
 */
@Suppress("MagicNumber")
fun destinationPoint(
    latitude: Double,
    longitude: Double,
    bearingDeg: Double,
    distanceMeters: Double,
): Pair<Double, Double> {
    val angular = distanceMeters / EARTH_RADIUS_METERS
    val theta = bearingDeg * PI / 180.0
    val lat1 = latitude * PI / 180.0
    val lon1 = longitude * PI / 180.0
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
    val lon2 = lon1 + atan2(sin(theta) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
    val lonDeg = ((lon2 * 180.0 / PI) + 540.0) % 360.0 - 180.0
    return (lat2 * 180.0 / PI) to lonDeg
}

/** Converts a protobuf ground track (1e-5 degree units) to degrees. */
fun groundTrackToDegrees(groundTrack: Int?): Double? = groundTrack?.let { it * GeoConstants.HEADING_DEG }

private fun TrackerFix.isDrawable(): Boolean = latitude in -MAX_LATITUDE..MAX_LATITUDE &&
    longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
    !(latitude == 0.0 && longitude == 0.0) &&
    timeSeconds > 0

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** Keeps the newest fix of each run that stays within [radius] of the run's first fix, as the per-node track does. */
private fun mergeStationary(fixes: List<TrackerFix>, radius: Double): List<TrackerFix> {
    val out = ArrayList<TrackerFix>(fixes.size)
    var runStart: TrackerFix? = null
    for (fix in fixes) {
        val start = runStart
        if (start != null && latLongToMeter(start.latitude, start.longitude, fix.latitude, fix.longitude) <= radius) {
            out[out.lastIndex] = fix
        } else {
            out += fix
            runStart = fix
        }
    }
    return out
}

/** Splits [points] into solid runs and dashed jump legs, in order. */
private fun segment(points: List<TrailPoint>, rules: TrackerRules): List<TrailSegment> {
    if (points.size < 2) return emptyList()
    val segments = mutableListOf<TrailSegment>()
    var run = mutableListOf(points.first())
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        if (isJump(a, b, rules)) {
            if (run.size > 1) segments += TrailSegment(run, jump = false)
            segments += TrailSegment(listOf(a, b), jump = true)
            run = mutableListOf(b)
        } else {
            run += b
        }
    }
    if (run.size > 1) segments += TrailSegment(run, jump = false)
    return segments
}

/** The node's own reported speed when it sent one, else the speed over its newest travelled leg. */
private fun currentSpeed(fixes: List<TrackerFix>, rules: TrackerRules): Double? {
    val reported = fixes.last().groundSpeedKmh?.let { it / KMH_PER_MPS }
    val a = fixes.getOrNull(fixes.lastIndex - 1)?.toPoint()
    val b = fixes.last().toPoint()
    return when {
        reported != null -> reported
        a == null || isJump(a, b, rules) -> null
        else -> latLongToMeter(a.latitude, a.longitude, b.latitude, b.longitude) / (b.timeSeconds - a.timeSeconds)
    }
}

@Suppress("ReturnCount")
private fun directionLine(
    newest: TrackerFix,
    fixes: List<TrackerFix>,
    speed: Double?,
    nowSeconds: Long,
    rules: TrackerRules,
): DirectionLine? {
    if (speed == null || speed < rules.movingSpeedMetersPerSecond) return null
    if (nowSeconds - newest.timeSeconds > rules.directionMaxAgeSeconds) return null
    val heading =
        newest.groundTrackDeg
            ?: fixes.getOrNull(fixes.lastIndex - 1)?.let { prev ->
                bearing(prev.latitude, prev.longitude, newest.latitude, newest.longitude)
            }
            ?: return null
    val length = (speed * rules.directionLookaheadSeconds).coerceIn(rules.directionMinMeters, rules.directionMaxMeters)
    val (lat, lon) = destinationPoint(newest.latitude, newest.longitude, heading, length)
    return DirectionLine(from = newest.toPoint(), toLatitude = lat, toLongitude = lon, headingDeg = heading)
}

private fun TrackerFix.toPoint() = TrailPoint(latitude, longitude, timeSeconds)

private const val KMH_PER_MPS = 3.6

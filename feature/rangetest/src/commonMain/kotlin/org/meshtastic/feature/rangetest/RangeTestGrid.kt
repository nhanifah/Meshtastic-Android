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

import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSampleKind
import org.meshtastic.core.ui.util.RangeTestHeatPoint
import org.meshtastic.core.ui.util.RangeTestLatLng
import org.meshtastic.core.ui.util.RangeTestMapCell
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapMode
import org.meshtastic.core.ui.util.RangeTestMapPoint
import org.meshtastic.core.ui.util.RangeTestMapTarget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** Aggregated readings of one grid cell. */
data class RangeTestCellStats(
    val id: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val received: Int,
    val missed: Int,
    val avgSnr: Float?,
    val minSnr: Float?,
    val avgRssi: Float?,
    val minRssi: Int?,
    val avgDistanceM: Double?,
) {
    val successRate: Float
        get() = if (received + missed == 0) 0f else received.toFloat() / (received + missed)
}

/** Received vs missed beacons within one distance band from the sender. */
data class RangeTestDistanceBucket(val fromM: Int, val toM: Int?, val received: Int, val missed: Int)

data class RangeTestSummary(
    val received: Int = 0,
    val missed: Int = 0,
    val relayed: Int = 0,
    val maxDirectDistanceM: Double? = null,
    val avgSnr: Float? = null,
    val avgRssi: Float? = null,
    val buckets: List<RangeTestDistanceBucket> = emptyList(),
) {
    val successRate: Float
        get() = if (received + missed == 0) 0f else received.toFloat() / (received + missed)
}

/**
 * Pure aggregation from recorded samples to what the map and summary show. Cells are squares of [cellSizeM] metres in a
 * local equirectangular projection anchored at the sender, which is accurate to well under a percent across the few
 * tens of kilometres a LoRa range test covers.
 */
object RangeTestGrid {
    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val CELL_ALPHA = 0x99
    private const val RGB_MASK = 0x00FFFFFF
    private const val ALPHA_SHIFT = 24
    private const val HALF_TURN_DEG = 180.0
    private const val MIN_COS = 1e-6
    private const val POOR_SUCCESS = 0.5f
    private const val VERY_POOR_SUCCESS = 0.2f
    private const val RING_HEADROOM = 1.2
    private const val MIN_RINGS = 2
    private val RINGS_M = listOf(500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0)
    private val BUCKET_EDGES_M = listOf(0, 500, 1_000, 2_000, 5_000, 10_000)

    /** Received beacons that count for range (direct, or of unknown hop count) plus every missed one. */
    fun signalSamples(samples: List<RangeTestSampleEntity>, includeRelayed: Boolean): List<RangeTestSampleEntity> =
        samples.filter {
            when (it.kind) {
                RangeTestSampleKind.MISSED -> true
                RangeTestSampleKind.RECEIVED -> includeRelayed || isDirect(it)
                else -> false
            }
        }

    fun isDirect(sample: RangeTestSampleEntity): Boolean = sample.hops == null || sample.hops == 0

    fun cells(
        samples: List<RangeTestSampleEntity>,
        originLat: Double,
        originLon: Double,
        cellSizeM: Int,
    ): List<RangeTestCellStats> {
        val dLat = cellSizeM / METERS_PER_DEGREE_LAT
        val dLon = cellSizeM / (METERS_PER_DEGREE_LAT * cos(originLat * PI / HALF_TURN_DEG)).coerceAtLeast(MIN_COS)
        return samples
            .groupBy {
                floor((it.latitude - originLat) / dLat).toInt() to floor((it.longitude - originLon) / dLon).toInt()
            }
            .map { (key, members) ->
                val (i, j) = key
                val received = members.filter { it.kind == RangeTestSampleKind.RECEIVED }
                val snrs = received.mapNotNull { it.snr }
                val rssis = received.mapNotNull { it.rssi }
                val distances = members.mapNotNull { it.distanceM }
                RangeTestCellStats(
                    id = "$i:$j",
                    south = originLat + i * dLat,
                    west = originLon + j * dLon,
                    north = originLat + (i + 1) * dLat,
                    east = originLon + (j + 1) * dLon,
                    received = received.size,
                    missed = members.size - received.size,
                    avgSnr = snrs.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    minSnr = snrs.minOrNull(),
                    avgRssi = rssis.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                    minRssi = rssis.minOrNull(),
                    avgDistanceM = distances.takeIf { it.isNotEmpty() }?.average(),
                )
            }
    }

    /**
     * A cell's band is the worse of its average signal and its delivery rate: a cell that averages a fine SNR but lost
     * most beacons is still a weak spot.
     */
    fun cellBand(cell: RangeTestCellStats, metric: RangeTestMetric): SignalBand {
        if (cell.received == 0) return SignalBand.MISSED
        val value = metricValue(metric, cell.avgSnr, cell.avgRssi)
        val signalBand = value?.let { RangeTestSignal.band(metric, it) } ?: SignalBand.FAIR
        val lossBand =
            when {
                cell.successRate < VERY_POOR_SUCCESS -> SignalBand.VERY_WEAK
                cell.successRate < POOR_SUCCESS -> SignalBand.WEAK
                else -> SignalBand.STRONG
            }
        return if (lossBand.ordinal > signalBand.ordinal) lossBand else signalBand
    }

    fun sampleBand(sample: RangeTestSampleEntity, metric: RangeTestMetric): SignalBand {
        if (sample.kind == RangeTestSampleKind.MISSED) return SignalBand.MISSED
        val value = metricValue(metric, sample.snr, sample.rssi?.toFloat())
        return value?.let { RangeTestSignal.band(metric, it) } ?: SignalBand.FAIR
    }

    @Suppress("LongParameterList")
    fun mapData(
        samples: List<RangeTestSampleEntity>,
        target: RangeTestMapTarget?,
        mode: RangeTestMapMode,
        metric: RangeTestMetric,
        cellSizeM: Int,
        includeRelayed: Boolean,
        receiver: RangeTestLatLng?,
    ): Pair<RangeTestMapData, List<RangeTestCellStats>> {
        val signal = signalSamples(samples, includeRelayed)
        val originLat = target?.latitude ?: signal.firstOrNull()?.latitude ?: 0.0
        val originLon = target?.longitude ?: signal.firstOrNull()?.longitude ?: 0.0
        val cellStats =
            if (mode == RangeTestMapMode.GRID) cells(signal, originLat, originLon, cellSizeM) else emptyList()
        val track =
            samples.filter { it.kind == RangeTestSampleKind.TRACK }.map { RangeTestLatLng(it.latitude, it.longitude) }
        val maxDistance = signal.mapNotNull { it.distanceM }.maxOrNull() ?: 0.0
        val data =
            RangeTestMapData(
                mode = mode,
                target = target,
                ringsMeters = if (target == null) emptyList() else rings(maxDistance),
                cells =
                cellStats.map {
                    RangeTestMapCell(
                        it.id,
                        it.south,
                        it.west,
                        it.north,
                        it.east,
                        withAlpha(cellBand(it, metric).argb),
                    )
                },
                points =
                if (mode == RangeTestMapMode.POINTS) {
                    signal.map {
                        RangeTestMapPoint(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            argb = sampleBand(it, metric).argb,
                            missed = it.kind == RangeTestSampleKind.MISSED,
                        )
                    }
                } else {
                    emptyList()
                },
                heat =
                if (mode == RangeTestMapMode.HEATMAP) {
                    signal.map { RangeTestHeatPoint(it.latitude, it.longitude, heatWeight(it, metric)) }
                } else {
                    emptyList()
                },
                track = track,
                receiver = receiver,
            )
        return data to cellStats
    }

    fun heatWeight(sample: RangeTestSampleEntity, metric: RangeTestMetric): Double = when {
        sample.kind == RangeTestSampleKind.MISSED -> 1.0

        else ->
            metricValue(metric, sample.snr, sample.rssi?.toFloat())?.let { RangeTestSignal.weakness(metric, it) }
                ?: 0.0
    }

    fun summary(samples: List<RangeTestSampleEntity>): RangeTestSummary {
        val received = samples.filter { it.kind == RangeTestSampleKind.RECEIVED }
        val direct = received.filter(::isDirect)
        val missed = samples.filter { it.kind == RangeTestSampleKind.MISSED }
        val buckets = BUCKET_EDGES_M.mapIndexed { index, from ->
            val to = BUCKET_EDGES_M.getOrNull(index + 1)
            fun inBucket(sample: RangeTestSampleEntity): Boolean {
                val d = sample.distanceM ?: return false
                return d >= from && (to == null || d < to)
            }
            RangeTestDistanceBucket(from, to, direct.count(::inBucket), missed.count(::inBucket))
        }
        return RangeTestSummary(
            received = direct.size,
            missed = missed.size,
            relayed = received.size - direct.size,
            maxDirectDistanceM = direct.mapNotNull { it.distanceM }.maxOrNull(),
            avgSnr = direct.mapNotNull { it.snr }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            avgRssi = direct.mapNotNull { it.rssi }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            buckets = buckets.filter { it.received + it.missed > 0 },
        )
    }

    fun rings(maxDistanceM: Double): List<Double> {
        val limit = max(maxDistanceM * RING_HEADROOM, RINGS_M[MIN_RINGS - 1])
        return RINGS_M.filter { it <= limit }
    }

    private fun metricValue(metric: RangeTestMetric, snr: Float?, rssi: Float?): Float? = when (metric) {
        RangeTestMetric.SNR -> snr
        RangeTestMetric.RSSI -> rssi
    }

    private fun withAlpha(argb: Int): Int = (argb and RGB_MASK) or (CELL_ALPHA shl ALPHA_SHIFT)
}

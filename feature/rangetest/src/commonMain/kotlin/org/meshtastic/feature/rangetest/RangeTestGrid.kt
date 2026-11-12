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
import org.meshtastic.core.ui.util.RangeTestLatLng
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapPin

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

/** Pure aggregation from recorded samples to what the map and summary show. */
object RangeTestGrid {
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

    /** The band of one sample, for the KML export's placemark colours. */
    fun sampleBand(sample: RangeTestSampleEntity, metric: RangeTestMetric): SignalBand {
        if (sample.kind == RangeTestSampleKind.MISSED) return SignalBand.MISSED
        val value =
            when (metric) {
                RangeTestMetric.SNR -> sample.snr
                RangeTestMetric.RSSI -> sample.rssi?.toFloat()
            }
        return value?.let { RangeTestSignal.band(metric, it) } ?: SignalBand.FAIR
    }

    /** The heatmap reading of one sample: its strength on the chosen metric, null when missed or unmeasured. */
    @Suppress("ReturnCount")
    fun heatInput(sample: RangeTestSampleEntity, metric: RangeTestMetric): RangeTestHeatInput? {
        if (sample.kind == RangeTestSampleKind.MISSED) {
            return RangeTestHeatInput(sample.latitude, sample.longitude, strength = null)
        }
        val value =
            when (metric) {
                RangeTestMetric.SNR -> sample.snr
                RangeTestMetric.RSSI -> sample.rssi?.toFloat()
            } ?: return null
        return RangeTestHeatInput(sample.latitude, sample.longitude, RangeTestSignal.strength(metric, value))
    }

    @Suppress("LongParameterList")
    fun mapData(
        samples: List<RangeTestSampleEntity>,
        sender: RangeTestMapPin?,
        receiver: RangeTestMapPin?,
        metric: RangeTestMetric,
        includeRelayed: Boolean,
        showRoute: Boolean,
        version: Long,
    ): RangeTestMapData {
        val inputs = signalSamples(samples, includeRelayed).mapNotNull { heatInput(it, metric) }
        val track =
            if (showRoute) {
                samples
                    .filter { it.kind == RangeTestSampleKind.TRACK }
                    .map { RangeTestLatLng(it.latitude, it.longitude) }
            } else {
                emptyList()
            }
        return RangeTestMapData(
            heat = RangeTestHeatmap.render(inputs, version),
            track = track,
            sender = sender,
            receiver = receiver,
        )
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
}

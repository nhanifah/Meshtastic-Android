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

import org.meshtastic.proto.MeshPacket

/** Which signal reading colors the map. */
enum class RangeTestMetric {
    SNR,
    RSSI,
}

/** Five quality bands, strongest first; [MISSED] is a beacon that never arrived. */
enum class SignalBand(val argb: Int) {
    STRONG(0xFF2E7D32.toInt()),
    GOOD(0xFF7CB342.toInt()),
    FAIR(0xFFFBC02D.toInt()),
    WEAK(0xFFF57C00.toInt()),
    VERY_WEAK(0xFFD32F2F.toInt()),
    MISSED(0xFF424242.toInt()),
}

/**
 * LoRa signal thresholds used for coloring and the heatmap. SNR bands follow the demodulation floor of the common
 * presets (LongFast ≈ -17.5 dB, so anything under -13 dB is close to dropping); RSSI bands are typical sub-GHz values
 * for a 125–250 kHz channel.
 */
object RangeTestSignal {
    private const val SNR_STRONG = 5f
    private const val SNR_GOOD = 0f
    private const val SNR_FAIR = -7f
    private const val SNR_WEAK = -13f
    private const val SNR_FLOOR = -20f

    private const val RSSI_STRONG = -90f
    private const val RSSI_GOOD = -105f
    private const val RSSI_FAIR = -115f
    private const val RSSI_WEAK = -122f
    private const val RSSI_FLOOR = -130f

    private val SNR_THRESHOLDS = floatArrayOf(SNR_STRONG, SNR_GOOD, SNR_FAIR, SNR_WEAK)
    private val RSSI_THRESHOLDS = floatArrayOf(RSSI_STRONG, RSSI_GOOD, RSSI_FAIR, RSSI_WEAK)

    private const val BAND_COUNT = 5f
    private const val WEAK_INDEX = 3

    private val SEQ_REGEX = Regex("""seq\s+(\d+)""", RegexOption.IGNORE_CASE)

    fun band(metric: RangeTestMetric, value: Float): SignalBand {
        // Ascending band order: the first threshold the value reaches names its band.
        val thresholds =
            when (metric) {
                RangeTestMetric.SNR -> SNR_THRESHOLDS
                RangeTestMetric.RSSI -> RSSI_THRESHOLDS
            }
        val index = thresholds.indexOfFirst { value >= it }
        return if (index == -1) SignalBand.VERY_WEAK else SignalBand.entries[index]
    }

    /**
     * Where a reading sits on the 0–1 strength scale the heatmap colours by: each band takes a fifth of the scale, so
     * 0.0–0.2 is very weak, 0.8–1.0 strong. Readings below the floor clamp to 0.
     */
    @Suppress("ReturnCount")
    fun strength(metric: RangeTestMetric, value: Float): Float {
        val (thresholds, floor) =
            when (metric) {
                RangeTestMetric.SNR -> SNR_THRESHOLDS to SNR_FLOOR
                RangeTestMetric.RSSI -> RSSI_THRESHOLDS to RSSI_FLOOR
            }
        // Ascending edges: floor, weak, fair, good, strong, and one band-width above strong.
        val strong = thresholds[0]
        val good = thresholds[1]
        val edges = floatArrayOf(floor, thresholds[WEAK_INDEX], thresholds[2], good, strong, strong + (strong - good))
        if (value <= edges[0]) return 0f
        for (i in 0 until edges.size - 1) {
            if (value < edges[i + 1]) return (i + (value - edges[i]) / (edges[i + 1] - edges[i])) / BAND_COUNT
        }
        return 1f
    }

    /** The sequence number of a firmware Range Test beacon ("seq 42"), or null for any other payload. */
    fun parseSeq(payload: String): Int? = SEQ_REGEX.find(payload)?.groupValues?.get(1)?.toIntOrNull()

    /** Hops the packet travelled, or null when the sender's firmware predates hop_start. */
    fun hops(packet: MeshPacket): Int? {
        val start = packet.hop_start
        val limit = packet.hop_limit
        return if (start == 0 || limit > start) null else start - limit
    }

    /** `rx_rssi` is 0 when the radio did not report one. */
    fun rssiOrNull(packet: MeshPacket): Int? = packet.rx_rssi.takeIf { it != 0 }
}

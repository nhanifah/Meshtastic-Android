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

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

/**
 * The app's own range-test beacon, separate from the firmware Range Test module: sent on [PORT] with a payload of
 * `"MRT1 seq <n> int <seconds>"`. Firmware module beacons (`"seq <n>"` on RANGE_TEST_APP) are still understood, so
 * sessions recorded against a firmware sender keep working.
 */
object RangeTestBeacon {
    const val MAGIC = "MRT1"
    val PORT: PortNum = PortNum.PRIVATE_APP

    private val INTERVAL_REGEX = Regex("""\bint\s+(\d+)""")

    data class Parsed(val seq: Int?, val intervalSec: Int?)

    fun encode(seq: Int, intervalSec: Int): ByteString = "$MAGIC seq $seq int $intervalSec".encodeUtf8()

    /** The beacon in [packet], or null when it is not a range-test beacon at all. */
    @Suppress("ReturnCount")
    fun parse(packet: MeshPacket): Parsed? {
        val decoded = packet.decoded ?: return null
        return when (decoded.portnum) {
            PORT -> {
                val text = decoded.payload.utf8()
                if (!text.startsWith(MAGIC)) return null
                Parsed(
                    seq = RangeTestSignal.parseSeq(text),
                    intervalSec = INTERVAL_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                )
            }

            PortNum.RANGE_TEST_APP -> Parsed(seq = RangeTestSignal.parseSeq(decoded.payload.utf8()), intervalSec = null)

            else -> null
        }
    }
}

/**
 * Tracks the sender's sequence numbers to find beacons that never arrived.
 *
 * Every beacon that arrives advances the sequence, whether it came directly or through a relay: a relayed beacon was
 * received, so it must never be counted as missed. A sequence that goes backwards (sender restarted) or jumps
 * implausibly far is treated as a restart, not as losses.
 */
class BeaconSequence(private val maxGap: Int = DEFAULT_MAX_GAP) {
    private var lastSeq: Int? = null
    private var lastAt: Long = 0

    /** A sequence number that should have arrived but did not, with the time it was due. */
    data class Gap(val seq: Int, val dueAt: Long)

    fun reset() {
        lastSeq = null
        lastAt = 0
    }

    /** Records [seq] heard at [now] and returns the beacons skipped since the previous one. */
    @Suppress("ReturnCount")
    fun advance(seq: Int?, now: Long): List<Gap> {
        if (seq == null) return emptyList()
        val previous = lastSeq
        // A duplicate of the beacon just heard adds nothing.
        if (previous == seq) return emptyList()
        val gaps =
            if (previous == null || seq <= previous || seq - previous - 1 > maxGap) {
                emptyList()
            } else {
                val count = seq - previous - 1
                val step = (now - lastAt) / (count + 1)
                (1..count).map { Gap(seq = previous + it, dueAt = lastAt + step * it) }
            }
        lastSeq = seq
        lastAt = now
        return gaps
    }

    companion object {
        const val DEFAULT_MAX_GAP = 100
    }
}

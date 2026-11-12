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
 * The app's own range-test beacon, separate from the firmware Range Test module, sent on [PORT]:
 * - a beacon: `"MRT1 seq <n> int <seconds> id <run> [left <seconds>]"`;
 * - the end of a run: `"MRT1 end seq <last n> id <run>"`, sent when the sender stops.
 *
 * `id` changes on every run so the receiver can tell a restarted sender from lost beacons, `int` lets it notice missed
 * beacons while none arrive at all, and `left` (timed runs) tells it when to stop expecting more. Firmware module
 * beacons (`"seq <n>"` on RANGE_TEST_APP) are still understood, so sessions recorded against a firmware sender keep
 * working.
 */
object RangeTestBeacon {
    const val MAGIC = "MRT1"
    val PORT: PortNum = PortNum.PRIVATE_APP

    private val INTERVAL_REGEX = Regex("""\bint\s+(\d+)""")
    private val RUN_REGEX = Regex("""\bid\s+(\w+)""")
    private val LEFT_REGEX = Regex("""\bleft\s+(\d+)""")
    private val END_REGEX = Regex("""^$MAGIC\s+end\b""")

    data class Parsed(
        val seq: Int?,
        val intervalSec: Int? = null,
        val runId: String? = null,
        val leftSec: Int? = null,
        /** The sender stopped; [seq] is the last beacon it sent, and this message is not a beacon itself. */
        val isEnd: Boolean = false,
    )

    fun encode(seq: Int, intervalSec: Int, runId: String, leftSec: Int? = null): ByteString =
        ("$MAGIC seq $seq int $intervalSec id $runId" + leftSec?.let { " left $it" }.orEmpty()).encodeUtf8()

    fun encodeEnd(lastSeq: Int, runId: String): ByteString = "$MAGIC end seq $lastSeq id $runId".encodeUtf8()

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
                    runId = RUN_REGEX.find(text)?.groupValues?.get(1),
                    leftSec = LEFT_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                    isEnd = END_REGEX.containsMatchIn(text),
                )
            }

            PortNum.RANGE_TEST_APP -> Parsed(seq = RangeTestSignal.parseSeq(decoded.payload.utf8()))

            else -> null
        }
    }
}

/**
 * Works out which of the sender's beacons never arrived.
 *
 * Every beacon that arrives counts as received, whether it came directly or through a relay. Missed beacons are found
 * two ways:
 * - by time ([onTick]): once a beacon is overdue by more than a grace period it is declared missed, so losses show up
 *   live and while the receiver is out of range, not only when a later beacon happens to arrive;
 * - by sequence ([onBeacon]): numbers skipped between two received beacons.
 *
 * A beacon declared missed that still turns up (late, after relays or retries) is reported as recovered. A new run id
 * (or, for firmware beacons without one, a number far below the last) starts over instead of counting losses.
 */
class BeaconTracker(private val maxUntimedGap: Int = DEFAULT_MAX_UNTIMED_GAP) {
    /** A beacon that should have arrived but did not, with the time it was due. */
    data class Gap(val seq: Int, val dueAt: Long)

    data class Update(val missed: List<Gap> = emptyList(), val recovered: Int? = null, val received: Boolean = false)

    private var runId: String? = null
    private var lastSeq: Int? = null
    private var lastAt = 0L
    private var intervalMs: Long? = null
    private var endsAt: Long? = null

    /** Highest number either received or declared missed. */
    private var accounted = 0
    private val declaredMissed = LinkedHashSet<Int>()

    fun reset() {
        runId = null
        lastSeq = null
        lastAt = 0
        intervalMs = null
        endsAt = null
        accounted = 0
        declaredMissed.clear()
    }

    /** Records [beacon] heard at [now]. */
    @Suppress("ReturnCount")
    fun onBeacon(beacon: RangeTestBeacon.Parsed, now: Long): Update {
        val seq = beacon.seq ?: return Update()
        val previous = lastSeq
        val newRun =
            if (beacon.runId != null) {
                beacon.runId != runId
            } else {
                previous != null && seq + RESTART_BACKTRACK < previous
            }
        if (newRun) reset()
        runId = beacon.runId ?: runId
        beacon.intervalSec?.takeIf { it > 0 }?.let { intervalMs = it * MILLIS_PER_SECOND }
        beacon.leftSec?.let { endsAt = now + it * MILLIS_PER_SECOND }

        if (beacon.isEnd) {
            endsAt = now
            if (lastSeq == null) return Update()
            return Update(missed = declareUpTo(seq, now))
        }

        val last = lastSeq
        if (last == null) {
            lastSeq = seq
            lastAt = now
            accounted = seq
            return Update(received = true)
        }
        if (seq <= last) {
            // A duplicate (retries) adds nothing; an older beacon declared missed earlier has turned up after all.
            return if (declaredMissed.remove(seq)) Update(recovered = seq, received = true) else Update()
        }
        val skipped = seq - last - 1
        val missed =
            if (intervalMs == null && skipped > maxUntimedGap) {
                accounted = maxOf(accounted, seq - 1)
                emptyList()
            } else {
                declareUpTo(seq - 1, now, receivedAt = now, receivedSeq = seq)
            }
        val recovered = seq.takeIf { declaredMissed.remove(it) }
        lastSeq = seq
        lastAt = now
        accounted = maxOf(accounted, seq)
        return Update(missed = missed, recovered = recovered, received = true)
    }

    /** Beacons that became overdue by [now] without arriving. Needs the sender's interval (app beacons only). */
    fun onTick(now: Long): List<Gap> {
        val interval = intervalMs
        val last = lastSeq
        if (interval == null || last == null) return emptyList()
        val grace = interval / 2 + GRACE_MS
        val result = mutableListOf<Gap>()
        while (result.size < MAX_PER_TICK) {
            val next = accounted + 1
            val due = lastAt + (next - last) * interval
            val end = endsAt
            if (now < due + grace || (end != null && due > end)) break
            result += Gap(next, due)
            markMissed(next)
            accounted = next
        }
        return result
    }

    /** Declares every number after [accounted] up to [upTo] missed. */
    private fun declareUpTo(upTo: Int, now: Long, receivedAt: Long = now, receivedSeq: Int = upTo + 1): List<Gap> {
        val last = lastSeq
        if (last == null || upTo <= accounted) return emptyList()
        val interval = intervalMs
        val gaps =
            (accounted + 1..upTo).map { k ->
                val due =
                    if (interval != null) {
                        lastAt + (k - last) * interval
                    } else {
                        lastAt + (receivedAt - lastAt) * (k - last) / (receivedSeq - last)
                    }
                Gap(k, minOf(due, receivedAt))
            }
        gaps.forEach { markMissed(it.seq) }
        accounted = upTo
        return gaps
    }

    private fun markMissed(seq: Int) {
        declaredMissed += seq
        while (declaredMissed.size > MAX_REMEMBERED) declaredMissed.remove(declaredMissed.first())
    }

    companion object {
        const val DEFAULT_MAX_UNTIMED_GAP = 100

        /**
         * How long past its due time (on top of half an interval) a beacon may still arrive before it counts missed.
         */
        const val GRACE_MS = 5_000L
        private const val RESTART_BACKTRACK = 10
        private const val MAX_PER_TICK = 50
        private const val MAX_REMEMBERED = 512
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

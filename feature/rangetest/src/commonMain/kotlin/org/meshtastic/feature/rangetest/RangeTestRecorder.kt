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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.dao.RangeTestDao
import org.meshtastic.core.database.entity.RangeTestLocationSource
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSampleKind
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.database.entity.RangeTestSessionRole
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PhoneLocationProvider
import org.meshtastic.core.repository.PhoneLocationState
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.MeshPacket

/** The receiver's best current position and where it came from. */
data class RangeTestFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val source: String,
    val timeMillis: Long,
)

/** Live state of the (single) running recording, for the home screen and the live map. */
data class RangeTestRecorderState(
    val sessionId: Long? = null,
    val targetNodeNum: Int? = null,
    val targetName: String? = null,
    val received: Int = 0,
    val missed: Int = 0,
    val relayed: Int = 0,
    /** Beacons heard while the receiver had no position fix; they cannot be placed on the map. */
    val unplaced: Int = 0,
    val lastSnr: Float? = null,
    val lastRssi: Int? = null,
    val lastHops: Int? = null,
    val lastDistanceM: Double? = null,
    val lastHeardAt: Long? = null,
    val maxDirectDistanceM: Double? = null,
    val fix: RangeTestFix? = null,
    val phonePermissionGranted: Boolean = true,
) {
    val isRecording: Boolean
        get() = sessionId != null
}

/**
 * Records a range test: only beacons sent by one stationary node are kept (the app's own [RangeTestBeacon], or a
 * firmware Range Test module beacon), each tagged with the receiver's position (own radio GPS when it has a fresh fix,
 * otherwise the phone's GPS), RSSI, SNR and hop count.
 *
 * Beacons that never arrive (see [BeaconTracker]) become [RangeTestSampleKind.MISSED] samples placed where the receiver
 * was when the beacon was due — found live by time, so losses also show while the sender is out of range. A periodic
 * [RangeTestSampleKind.TRACK] fix records the route.
 *
 * Application-scoped so a recording survives leaving the screen; only one recording runs at a time.
 */
@Suppress("TooManyFunctions")
@Single
class RangeTestRecorder(
    private val serviceRepository: ServiceRepository,
    private val nodeRepository: NodeRepository,
    private val phoneLocationProvider: PhoneLocationProvider,
    private val rangeTestDao: RangeTestDao,
    private val applicationScope: ApplicationCoroutineScope,
    dispatchers: CoroutineDispatchers,
) {
    private val mutex = Mutex()

    /** Every coroutine of a recording runs on this one lane, so the bookkeeping below needs no further locking. */
    private val serial = dispatchers.default.limitedParallelism(1)
    private var job: Job? = null
    private val stateFlow = MutableStateFlow(RangeTestRecorderState())
    val state: StateFlow<RangeTestRecorderState> = stateFlow.asStateFlow()

    // Recording-local bookkeeping; touched only from the packet collector.
    private val tracker = BeaconTracker()
    private val recentFixes = ArrayDeque<RangeTestFix>()

    suspend fun start(target: Node) = mutex.withLock {
        if (job != null) return@withLock
        val now = nowMillis
        rangeTestDao.closeOpenSessionsOfRole(now, RangeTestSessionRole.RECEIVER)
        val position = target.validPosition?.let { target.latitude to target.longitude }
        val sessionId =
            rangeTestDao.insertSession(
                RangeTestSessionEntity(
                    targetNodeNum = target.num,
                    targetName = target.user.long_name.ifBlank { target.user.short_name },
                    targetLatitude = position?.first,
                    targetLongitude = position?.second,
                    startedAt = now,
                ),
            )
        tracker.reset()
        recentFixes.clear()
        stateFlow.value =
            RangeTestRecorderState(
                sessionId = sessionId,
                targetNodeNum = target.num,
                targetName = target.user.long_name.ifBlank { target.user.short_name },
            )
        job =
            applicationScope.launch(serial) {
                val targetPosition = MutableStateFlow(position)
                launch { trackFixes() }
                launch { trackTargetPosition(sessionId, target.num, targetPosition) }
                launch { recordTrack(sessionId) }
                launch { watchOverdue(sessionId, targetPosition) }
                serviceRepository.meshPacketFlow
                    .filter { it.from == target.num }
                    .collect { packet ->
                        val beacon = RangeTestBeacon.parse(packet) ?: return@collect
                        onBeacon(sessionId, packet, beacon, targetPosition.value)
                    }
            }
    }

    /** Marks sessions left open by a process death as ended, unless one is actually recording now. */
    suspend fun closeStaleSessions() = mutex.withLock {
        if (job == null) rangeTestDao.closeOpenSessionsOfRole(nowMillis, RangeTestSessionRole.RECEIVER)
    }

    suspend fun stop() = mutex.withLock {
        val sessionId = stateFlow.value.sessionId ?: return@withLock
        job?.cancel()
        job = null
        rangeTestDao.endSession(sessionId, nowMillis)
        stateFlow.value = RangeTestRecorderState()
    }

    private suspend fun trackFixes() {
        combine(
            phoneLocationProvider.locationUpdates().onStart { emit(PhoneLocationState(true, true, null)) },
            nodeRepository.ourNodeInfo,
        ) { phone, ourNode ->
            phone.permissionGranted to bestFix(phone, ourNode, nowMillis)
        }
            .catch { Logger.w(it) { "RangeTest location stream failed" } }
            .collect { (permission, fix) ->
                if (fix != null) {
                    recentFixes.addLast(fix)
                    while (recentFixes.size > MAX_RECENT_FIXES) recentFixes.removeFirst()
                }
                stateFlow.update { it.copy(fix = fix ?: it.fix, phonePermissionGranted = permission) }
            }
    }

    private suspend fun trackTargetPosition(
        sessionId: Long,
        targetNum: Int,
        out: MutableStateFlow<Pair<Double, Double>?>,
    ) {
        nodeRepository.nodeDBbyNum
            .map { nodes -> nodes[targetNum]?.takeIf { it.validPosition != null }?.let { it.latitude to it.longitude } }
            .distinctUntilChanged()
            .collect { position ->
                if (position != null && position != out.value) {
                    out.value = position
                    rangeTestDao.updateTargetPosition(sessionId, position.first, position.second)
                }
            }
    }

    private suspend fun recordTrack(sessionId: Long) {
        var last: RangeTestFix? = null
        while (currentCoroutineContext().isActive) {
            delay(TRACK_INTERVAL_MS)
            val fix = stateFlow.value.fix ?: continue
            val previous = last
            val moved =
                previous == null ||
                    latLongToMeter(previous.latitude, previous.longitude, fix.latitude, fix.longitude) >=
                    TRACK_MIN_MOVE_M ||
                    fix.timeMillis - previous.timeMillis >= TRACK_MAX_GAP_MS
            if (moved && fix != previous) {
                last = fix
                insert(listOf(fix.toSample(sessionId, RangeTestSampleKind.TRACK, nowMillis, distanceM = null)))
            }
        }
    }

    /** Declares beacons missed once they are overdue, so losses appear while nothing arrives at all. */
    private suspend fun watchOverdue(sessionId: Long, target: MutableStateFlow<Pair<Double, Double>?>) {
        while (currentCoroutineContext().isActive) {
            delay(OVERDUE_CHECK_MS)
            recordMissed(sessionId, tracker.onTick(nowMillis), target.value)
        }
    }

    private suspend fun recordMissed(sessionId: Long, gaps: List<BeaconTracker.Gap>, target: Pair<Double, Double>?) {
        if (gaps.isEmpty()) return
        insert(missedSamples(sessionId, gaps, target))
        stateFlow.update { it.copy(missed = it.missed + gaps.size) }
    }

    private suspend fun onBeacon(
        sessionId: Long,
        packet: MeshPacket,
        beacon: RangeTestBeacon.Parsed,
        target: Pair<Double, Double>?,
    ) {
        val now = nowMillis
        val update = tracker.onBeacon(beacon, now)
        recordMissed(sessionId, update.missed, target)
        update.recovered?.let { seq ->
            // Declared missed earlier, but it arrived after all (late, via relays or retries).
            rangeTestDao.deleteMissed(sessionId, seq)
            stateFlow.update { it.copy(missed = (it.missed - 1).coerceAtLeast(0)) }
        }
        // Duplicates (retries) and the sender's end-of-run notice are not beacons to record.
        if (!update.received) return

        val seq = beacon.seq
        val hops = RangeTestSignal.hops(packet)
        val direct = hops == null || hops == 0
        val fix = stateFlow.value.fix?.takeIf { now - it.timeMillis <= FIX_MAX_AGE_MS }
        val distance = fix?.let { f -> target?.let { latLongToMeter(f.latitude, f.longitude, it.first, it.second) } }

        val received = fix?.let {
            RangeTestSampleEntity(
                sessionId = sessionId,
                kind = RangeTestSampleKind.RECEIVED,
                timeMillis = now,
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyM = it.accuracyM,
                locationSource = it.source,
                seq = seq,
                rssi = RangeTestSignal.rssiOrNull(packet),
                snr = packet.rx_snr,
                hops = hops,
                distanceM = distance,
            )
        }
        insert(listOfNotNull(received))

        stateFlow.update { s ->
            s.copy(
                received = s.received + if (direct && received != null) 1 else 0,
                relayed = s.relayed + if (direct) 0 else 1,
                unplaced = s.unplaced + if (received == null) 1 else 0,
                lastSnr = packet.rx_snr,
                lastRssi = RangeTestSignal.rssiOrNull(packet),
                lastHops = hops,
                lastDistanceM = distance,
                lastHeardAt = now,
                maxDirectDistanceM =
                if (direct && distance != null) {
                    maxOf(distance, s.maxDirectDistanceM ?: 0.0)
                } else {
                    s.maxDirectDistanceM
                },
            )
        }
    }

    /** One [RangeTestSampleKind.MISSED] sample per skipped beacon, at the receiver fix closest to when it was due. */
    private fun missedSamples(
        sessionId: Long,
        gaps: List<BeaconTracker.Gap>,
        target: Pair<Double, Double>?,
    ): List<RangeTestSampleEntity> = gaps.mapNotNull { gap ->
        val fix = recentFixes.minByOrNull { kotlin.math.abs(it.timeMillis - gap.dueAt) } ?: return@mapNotNull null
        val distance = target?.let { latLongToMeter(fix.latitude, fix.longitude, it.first, it.second) }
        fix.toSample(sessionId, RangeTestSampleKind.MISSED, gap.dueAt, distance).copy(seq = gap.seq)
    }

    private suspend fun insert(samples: List<RangeTestSampleEntity>) {
        if (samples.isEmpty()) return
        try {
            rangeTestDao.insertSamples(samples)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "RangeTest sample insert failed" }
        }
    }

    private fun RangeTestFix.toSample(sessionId: Long, kind: String, at: Long, distanceM: Double?) =
        RangeTestSampleEntity(
            sessionId = sessionId,
            kind = kind,
            timeMillis = at,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            locationSource = source,
            distanceM = distanceM,
        )

    companion object {
        /** Own-radio GPS older than this is considered stale and the phone fix is used instead. */
        const val RADIO_FIX_MAX_AGE_MS = 60_000L
        private const val FIX_MAX_AGE_MS = 120_000L
        private const val TRACK_INTERVAL_MS = 5_000L
        private const val OVERDUE_CHECK_MS = 1_000L
        private const val TRACK_MIN_MOVE_M = 5.0
        private const val TRACK_MAX_GAP_MS = 30_000L
        private const val MAX_RECENT_FIXES = 720
        private const val MILLIS_PER_SECOND = 1000L

        /** Own radio GPS when fresh, otherwise the phone's GPS — the receiver-position rule of this feature. */
        @Suppress("ReturnCount")
        fun bestFix(phone: PhoneLocationState, ourNode: Node?, now: Long): RangeTestFix? {
            val radioTime = (ourNode?.position?.time ?: 0) * MILLIS_PER_SECOND
            if (ourNode?.validPosition != null && radioTime > 0 && now - radioTime <= RADIO_FIX_MAX_AGE_MS) {
                return RangeTestFix(
                    latitude = ourNode.latitude,
                    longitude = ourNode.longitude,
                    accuracyM = null,
                    source = RangeTestLocationSource.RADIO,
                    timeMillis = radioTime,
                )
            }
            val location = phone.location ?: return null
            return RangeTestFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = location.accuracyM,
                source = RangeTestLocationSource.PHONE,
                timeMillis = location.timeMillis,
            )
        }
    }
}

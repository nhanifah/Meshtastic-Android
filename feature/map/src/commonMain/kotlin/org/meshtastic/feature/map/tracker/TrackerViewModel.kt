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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.nowSeconds
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.MeshLog
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.MeshLogRepository
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.TrackerPrefs
import org.meshtastic.core.repository.TrackerSettings
import org.meshtastic.core.ui.util.toPosition
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.proto.PortNum
import kotlin.time.Duration.Companion.seconds

/** What the map draws for the tracker: the settings it was built with, and a trail per node that has one. */
data class TrackerState(val settings: TrackerSettings = TrackerSettings(), val trails: List<NodeTrail> = emptyList()) {
    val active: Boolean
        get() = settings.enabled && trails.isNotEmpty()
}

/**
 * The trails to draw for the nodes the map is showing. A trail is a history, so it follows its node: a node hidden by
 * the map's filters hides its trail too, and a trail for a node not on the map at all is not drawn.
 */
fun TrackerState.visibleTrails(visibleNodes: List<Node>): List<NodeTrail> {
    if (!settings.enabled || trails.isEmpty()) return emptyList()
    val shown = visibleNodes.mapTo(HashSet()) { it.num }
    return trails.filter { it.nodeNum in shown }
}

/**
 * Builds the tracker's trails for the main map from the position log.
 *
 * Reads nothing while the tracker is off: the log query is only subscribed under an enabled setting, so switching it
 * off costs no database work at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class TrackerViewModel(
    trackerPrefs: TrackerPrefs,
    meshLogRepository: MeshLogRepository,
    nodeRepository: NodeRepository,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {

    /** Ticks so a trail's oldest end keeps falling off while no new fix arrives. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(nowSeconds)
            delay(CLOCK_TICK)
        }
    }

    private val settings = trackerPrefs.settings

    val state: StateFlow<TrackerState> =
        settings
            .flatMapLatest { current ->
                if (!current.enabled) {
                    flowOf(TrackerState(settings = current))
                } else {
                    // The window is fixed when the query starts; the clock trims it in memory from then on. A margin
                    // keeps a fix logged just after a node's own clock time inside the window.
                    val sinceMillis = (nowSeconds - current.tailSeconds - QUERY_MARGIN_SECONDS) * MILLIS_PER_SECOND
                    combine(
                        meshLogRepository
                            .getLogsOnPortSince(PortNum.POSITION_APP.value, sinceMillis, MAX_TRACKER_LOGS)
                            .conflate(),
                        nodeRepository.myNodeInfo.map { it?.myNodeNum }.distinctUntilChanged(),
                        clock,
                    ) { logs, myNodeNum, now ->
                        TrackerState(
                            settings = current,
                            trails = buildTrails(logs, myNodeNum, now, current.tailSeconds),
                        )
                    }
                        .flowOn(dispatchers.default)
                }
            }
            .stateInWhileSubscribed(initialValue = TrackerState(settings = settings.value))

    companion object {
        /** Upper bound on log rows read for one tail, so an 18-hour tail on a busy mesh stays bounded. */
        const val MAX_TRACKER_LOGS = 20_000

        private val CLOCK_TICK = 30.seconds
        private const val QUERY_MARGIN_SECONDS = 5 * 60L
        private const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Groups position [logs] by sender and builds one trail each. A log from this device carries [MeshLog.NODE_NUM_LOCAL],
 * which is folded into [myNodeNum] so the local node's trail is one trail, not two.
 */
fun buildTrails(logs: List<MeshLog>, myNodeNum: Int?, nowSeconds: Long, tailSeconds: Long): List<NodeTrail> = logs
    .mapNotNull { log -> log.toTrackerFix()?.let { fix -> log.senderNum(myNodeNum) to fix } }
    .groupBy({ it.first }, { it.second })
    .mapNotNull { (nodeNum, fixes) -> buildNodeTrail(nodeNum, fixes, nowSeconds, tailSeconds) }
    .sortedBy { it.nodeNum }

private fun MeshLog.senderNum(myNodeNum: Int?): Int =
    if (fromNum == MeshLog.NODE_NUM_LOCAL && myNodeNum != null) myNodeNum else fromNum

@Suppress("ReturnCount")
private fun MeshLog.toTrackerFix(): TrackerFix? {
    val position = meshPacket?.toPosition() ?: return null
    val latI = position.latitude_i ?: return null
    val lonI = position.longitude_i ?: return null
    // A node without a clock sends time 0; the moment we logged it is the best time we have.
    val time = position.time.toLong().takeIf { it > 0 } ?: (received_date / MILLIS)
    return TrackerFix(
        latitude = latI * DEG_PER_UNIT,
        longitude = lonI * DEG_PER_UNIT,
        timeSeconds = time,
        groundSpeedKmh = position.ground_speed,
        groundTrackDeg = groundTrackToDegrees(position.ground_track),
    )
}

private const val DEG_PER_UNIT = 1e-7
private const val MILLIS = 1000L

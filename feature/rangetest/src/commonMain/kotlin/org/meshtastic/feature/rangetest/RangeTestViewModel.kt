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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.LocaleUnitsProvider
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.database.dao.RangeTestDao
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed

/** A node the user can lock the range test onto. */
data class RangeTestCandidate(val node: Node, val hasPosition: Boolean)

@KoinViewModel
class RangeTestViewModel(
    private val recorder: RangeTestRecorder,
    private val rangeTestDao: RangeTestDao,
    nodeRepository: NodeRepository,
    localeUnitsProvider: LocaleUnitsProvider,
) : ViewModel() {

    val recorderState: StateFlow<RangeTestRecorderState> = recorder.state

    val units: StateFlow<MeasurementSystem> = localeUnitsProvider.measurementSystem

    val sessions: StateFlow<List<RangeTestSessionEntity>> =
        rangeTestDao.getSessions().stateInWhileSubscribed(initialValue = emptyList())

    /** Every other node, most recently heard first — the stationary sender is one of them. */
    val candidates: StateFlow<List<RangeTestCandidate>> =
        combine(nodeRepository.nodeDBbyNum, nodeRepository.myNodeInfo) { nodes, me ->
            nodes.values
                .filter { it.num != me?.myNodeNum }
                .sortedByDescending { it.lastHeard }
                .map { RangeTestCandidate(it, it.validPosition != null) }
        }
            .stateInWhileSubscribed(initialValue = emptyList())

    init {
        safeLaunch(tag = "rangeTestCloseStale") { recorder.closeStaleSessions() }
    }

    fun start(node: Node) {
        safeLaunch(tag = "rangeTestStart") { recorder.start(node) }
    }

    fun stop() {
        safeLaunch(tag = "rangeTestStop") { recorder.stop() }
    }

    fun deleteSession(sessionId: Long) {
        if (recorder.state.value.sessionId == sessionId) return
        safeLaunch(tag = "rangeTestDelete") { rangeTestDao.deleteSession(sessionId) }
    }
}

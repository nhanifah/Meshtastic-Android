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
package org.meshtastic.feature.node.scanner

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.nowSeconds
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import kotlin.time.Duration.Companion.seconds

data class RfScannerUiState(
    val window: RfScanWindow = RfScanWindow.HOURS_2,
    val sort: RfScanSort = RfScanSort.SIGNAL,
    val result: RfScanResult = RfScanResult(),
)

@KoinViewModel
class RfScannerViewModel(nodeRepository: NodeRepository, nodeManager: NodeManager) : ViewModel() {

    private val window = MutableStateFlow(RfScanWindow.HOURS_2)
    private val sort = MutableStateFlow(RfScanSort.SIGNAL)

    // Nodes age out of the window even when nothing new arrives, so the clock ticks on its own.
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(nowSeconds)
            delay(CLOCK_TICK)
        }
    }

    // Same gate as the node list's unheard filter: until the firmware is known to report the flag and the node DB
    // install has finished, cached rows may still carry a false from an earlier session.
    private val trustHeardFlag: Flow<Boolean> =
        combine(nodeManager.reportsHeardOnCurrentLora, nodeManager.isNodeDbReady) { reports, ready -> reports && ready }

    private val settings: Flow<Pair<RfScanWindow, RfScanSort>> = combine(window, sort) { w, s -> w to s }

    val uiState: StateFlow<RfScannerUiState> =
        combine(nodeRepository.nodeDBbyNum, nodeRepository.myNodeInfo, trustHeardFlag, settings, clock) {
                nodes,
                myInfo,
                trust,
                (w, s),
                now,
            ->
            RfScannerUiState(
                window = w,
                sort = s,
                result =
                RfScanClassifier.scan(
                    nodes = nodes.values,
                    ourNodeNum = myInfo?.myNodeNum,
                    nowSeconds = now,
                    window = w,
                    sort = s,
                    trustHeardFlag = trust,
                ),
            )
        }
            .stateInWhileSubscribed(initialValue = RfScannerUiState())

    fun setWindow(value: RfScanWindow) {
        window.value = value
    }

    fun setSort(value: RfScanSort) {
        sort.value = value
    }

    private companion object {
        val CLOCK_TICK = 30.seconds
    }
}

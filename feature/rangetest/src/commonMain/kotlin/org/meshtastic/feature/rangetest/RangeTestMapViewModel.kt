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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.common.util.LocaleUnitsProvider
import org.meshtastic.core.common.util.MeasurementSystem
import org.meshtastic.core.database.dao.RangeTestDao
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.ui.util.RangeTestLatLng
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapMode
import org.meshtastic.core.ui.util.RangeTestMapTarget
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed

/** User-adjustable map presentation. */
data class RangeTestMapSettings(
    val mode: RangeTestMapMode = RangeTestMapMode.GRID,
    val metric: RangeTestMetric = RangeTestMetric.SNR,
    val cellSizeM: Int = DEFAULT_CELL_SIZE_M,
    val includeRelayed: Boolean = false,
) {
    companion object {
        const val DEFAULT_CELL_SIZE_M = 50
        val CELL_SIZES_M = listOf(25, 50, 100, 200)
    }
}

data class RangeTestMapUiState(
    val session: RangeTestSessionEntity? = null,
    val settings: RangeTestMapSettings = RangeTestMapSettings(),
    val mapData: RangeTestMapData = RangeTestMapData(),
    val cells: Map<String, RangeTestCellStats> = emptyMap(),
    val summary: RangeTestSummary = RangeTestSummary(),
    val live: RangeTestRecorderState? = null,
    val selectedCell: RangeTestCellStats? = null,
    val loading: Boolean = true,
)

enum class RangeTestExportResult {
    SUCCESS,
    FAILURE,
}

@KoinViewModel
class RangeTestMapViewModel(
    @InjectedParam private val sessionId: Long,
    private val rangeTestDao: RangeTestDao,
    private val fileService: FileService,
    recorder: RangeTestRecorder,
    localeUnitsProvider: LocaleUnitsProvider,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val settings = MutableStateFlow(RangeTestMapSettings())
    private val selectedCellId = MutableStateFlow<String?>(null)
    private val exportEvents = MutableSharedFlow<RangeTestExportResult>(extraBufferCapacity = 1)
    val exportResults: SharedFlow<RangeTestExportResult> = exportEvents.asSharedFlow()

    val units: StateFlow<MeasurementSystem> = localeUnitsProvider.measurementSystem

    private val live = recorder.state.map { state -> state.takeIf { it.sessionId == sessionId } }

    val uiState: StateFlow<RangeTestMapUiState> =
        combine(
            rangeTestDao.getSessionFlow(sessionId),
            rangeTestDao.getSamples(sessionId),
            settings,
            live,
            selectedCellId,
        ) { session, samples, settings, live, selectedId ->
            val targetLat = session?.targetLatitude
            val targetLon = session?.targetLongitude
            val target =
                if (session != null && targetLat != null && targetLon != null) {
                    RangeTestMapTarget(targetLat, targetLon, session.targetName)
                } else {
                    null
                }
            val receiver = live?.fix?.let { RangeTestLatLng(it.latitude, it.longitude) }
            val (mapData, cellStats) =
                RangeTestGrid.mapData(
                    samples = samples,
                    target = target,
                    mode = settings.mode,
                    metric = settings.metric,
                    cellSizeM = settings.cellSizeM,
                    includeRelayed = settings.includeRelayed,
                    receiver = receiver,
                )
            val cells = cellStats.associateBy { it.id }
            RangeTestMapUiState(
                session = session,
                settings = settings,
                mapData = mapData,
                cells = cells,
                summary = RangeTestGrid.summary(samples),
                live = live,
                selectedCell = selectedId?.let(cells::get),
                loading = false,
            )
        }
            .flowOn(dispatchers.default)
            .stateInWhileSubscribed(initialValue = RangeTestMapUiState())

    fun setMode(mode: RangeTestMapMode) = settings.update { it.copy(mode = mode) }

    fun setMetric(metric: RangeTestMetric) = settings.update { it.copy(metric = metric) }

    fun setCellSize(meters: Int) {
        selectedCellId.value = null
        settings.update { it.copy(cellSizeM = meters) }
    }

    fun setIncludeRelayed(include: Boolean) = settings.update { it.copy(includeRelayed = include) }

    fun selectCell(id: String?) {
        selectedCellId.value = id
    }

    fun exportCsv(uri: CommonUri) {
        safeLaunch(tag = "rangeTestExportCsv") {
            val samples = rangeTestDao.getSamplesSnapshot(sessionId)
            report(fileService.write(uri) { sink -> RangeTestExport.writeCsv(sink, samples) })
        }
    }

    fun exportKml(uri: CommonUri) {
        safeLaunch(tag = "rangeTestExportKml") {
            val session = rangeTestDao.getSessionFlow(sessionId).first() ?: return@safeLaunch report(false)
            val samples = rangeTestDao.getSamplesSnapshot(sessionId)
            val metric = settings.value.metric
            report(fileService.write(uri) { sink -> RangeTestExport.writeKml(sink, session, samples, metric) })
        }
    }

    private fun report(ok: Boolean) {
        exportEvents.tryEmit(if (ok) RangeTestExportResult.SUCCESS else RangeTestExportResult.FAILURE)
    }
}

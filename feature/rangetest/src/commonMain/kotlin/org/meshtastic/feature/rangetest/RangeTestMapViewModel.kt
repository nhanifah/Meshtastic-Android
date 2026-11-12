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
import org.meshtastic.core.ui.util.RangeTestMapData
import org.meshtastic.core.ui.util.RangeTestMapPin
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed

/** User-adjustable map presentation. */
data class RangeTestMapSettings(
    val metric: RangeTestMetric = RangeTestMetric.SNR,
    val includeRelayed: Boolean = false,
    val showRoute: Boolean = true,
)

data class RangeTestMapUiState(
    val session: RangeTestSessionEntity? = null,
    val settings: RangeTestMapSettings = RangeTestMapSettings(),
    val mapData: RangeTestMapData = RangeTestMapData(),
    val summary: RangeTestSummary = RangeTestSummary(),
    val live: RangeTestRecorderState? = null,
    val loading: Boolean = true,
)

enum class RangeTestExportResult {
    SUCCESS,
    FAILURE,
}

/** The heatmap and summary of one session, recomputed only when samples or settings change. */
private data class RangeTestRendered(
    val session: RangeTestSessionEntity?,
    val settings: RangeTestMapSettings,
    val mapData: RangeTestMapData,
    val summary: RangeTestSummary,
)

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
    private val exportEvents = MutableSharedFlow<RangeTestExportResult>(extraBufferCapacity = 1)
    val exportResults: SharedFlow<RangeTestExportResult> = exportEvents.asSharedFlow()

    val units: StateFlow<MeasurementSystem> = localeUnitsProvider.measurementSystem

    private val live = recorder.state.map { state -> state.takeIf { it.sessionId == sessionId } }

    private val rendered =
        combine(rangeTestDao.getSessionFlow(sessionId), rangeTestDao.getSamples(sessionId), settings) {
                session,
                samples,
                settings,
            ->
            val targetLat = session?.targetLatitude
            val targetLon = session?.targetLongitude
            val sender =
                if (session != null && targetLat != null && targetLon != null) {
                    RangeTestMapPin(targetLat, targetLon, session.targetName)
                } else {
                    null
                }
            val version = (samples.lastOrNull()?.id ?: 0L) * VERSION_SALT + samples.size + settings.hashCode()
            RangeTestRendered(
                session = session,
                settings = settings,
                mapData =
                RangeTestGrid.mapData(
                    samples = samples,
                    sender = sender,
                    receiver = null,
                    metric = settings.metric,
                    includeRelayed = settings.includeRelayed,
                    showRoute = settings.showRoute,
                    version = version,
                ),
                summary = RangeTestGrid.summary(samples),
            )
        }
            .flowOn(dispatchers.default)

    val uiState: StateFlow<RangeTestMapUiState> =
        combine(rendered, live) { r, live ->
            // The receiver pin's label is filled in by the screen, which owns the localized text.
            val receiver = live?.fix?.let { RangeTestMapPin(it.latitude, it.longitude, label = "") }
            RangeTestMapUiState(
                session = r.session,
                settings = r.settings,
                mapData = r.mapData.copy(receiver = receiver),
                summary = r.summary,
                live = live,
                loading = false,
            )
        }
            .stateInWhileSubscribed(initialValue = RangeTestMapUiState())

    fun setMetric(metric: RangeTestMetric) = settings.update { it.copy(metric = metric) }

    fun setIncludeRelayed(include: Boolean) = settings.update { it.copy(includeRelayed = include) }

    fun setShowRoute(show: Boolean) = settings.update { it.copy(showRoute = show) }

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

    private companion object {
        const val VERSION_SALT = 1_000_003L
    }

    private fun report(ok: Boolean) {
        exportEvents.tryEmit(if (ok) RangeTestExportResult.SUCCESS else RangeTestExportResult.FAILURE)
    }
}

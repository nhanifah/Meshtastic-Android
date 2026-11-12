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
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed

/**
 * Backs the Range Test hub and the two live screens: the receiver's recording, the sender's beacon run, and the list of
 * recorded sessions.
 */
@KoinViewModel
class RangeTestViewModel(
    private val recorder: RangeTestRecorder,
    private val sender: RangeTestBeaconSender,
    private val channels: RangeTestChannels,
    private val rangeTestDao: RangeTestDao,
    localeUnitsProvider: LocaleUnitsProvider,
) : ViewModel() {

    val recorderState: StateFlow<RangeTestRecorderState> = recorder.state

    val senderState: StateFlow<RangeTestSenderState> = sender.state

    val units: StateFlow<MeasurementSystem> = localeUnitsProvider.measurementSystem

    val sessions: StateFlow<List<RangeTestSessionEntity>> =
        rangeTestDao.getSessions().stateInWhileSubscribed(initialValue = emptyList())

    /** The sender's channel slot, still present on the radio, that the "remove channel" offer would delete. */
    val senderChannel: StateFlow<RangeTestChannelOption?> =
        combine(sender.state, channels.channels) { state, list ->
            val config = state.config ?: return@combine null
            list.firstOrNull { it.index == config.channelIndex && it.name == config.channelName && !it.isPrimary }
        }
            .stateInWhileSubscribed(initialValue = null)

    init {
        safeLaunch(tag = "rangeTestCloseStale") {
            recorder.closeStaleSessions()
            sender.closeStaleSessions()
        }
    }

    fun stopRecording() {
        safeLaunch(tag = "rangeTestStop") { recorder.stop() }
    }

    fun stopSending() {
        safeLaunch(tag = "rangeTestStopSending") { sender.stop() }
    }

    fun dismissSender() = sender.dismiss()

    fun removeChannel(index: Int) {
        safeLaunch(tag = "rangeTestRemoveChannel") { channels.removeChannel(index) }
    }

    fun deleteSession(sessionId: Long) {
        if (recorder.state.value.sessionId == sessionId) return
        safeLaunch(tag = "rangeTestDelete") { rangeTestDao.deleteSession(sessionId) }
    }
}

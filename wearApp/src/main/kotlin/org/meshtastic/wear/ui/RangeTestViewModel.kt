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
package org.meshtastic.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.util.getChannel
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.feature.rangetest.RangeTestBeaconMode
import org.meshtastic.feature.rangetest.RangeTestBeaconSender
import org.meshtastic.feature.rangetest.RangeTestRecorder
import org.meshtastic.feature.rangetest.RangeTestRecorderState
import org.meshtastic.feature.rangetest.RangeTestSenderConfig
import org.meshtastic.feature.rangetest.RangeTestSenderState

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

data class WatchChannel(val index: Int, val name: String)

/** What the watch's send form is set to before a run starts. */
data class SenderDraft(
    val channelIndex: Int = 0,
    val intervalSec: Int = RangeTestSenderConfig.MIN_INTERVAL_SEC,
    val mode: RangeTestBeaconMode = RangeTestBeaconMode.DIRECT,
)

/**
 * Range test on the wrist, on the phone app's own sender and recorder: send beacons from the radio (down to every 2 s,
 * for aiming a directional antenna), or record one stationary node's beacons with a live SNR/RSSI readout.
 */
@KoinViewModel
class WatchRangeTestViewModel(
    private val sender: RangeTestBeaconSender,
    private val recorder: RangeTestRecorder,
    private val nodeRepository: NodeRepository,
    radioConfigRepository: RadioConfigRepository,
) : ViewModel() {
    val senderState: StateFlow<RangeTestSenderState> = sender.state
    val recorderState: StateFlow<RangeTestRecorderState> = recorder.state

    val channels: StateFlow<List<WatchChannel>> =
        radioConfigRepository.channelSetFlow
            .map { set ->
                (0 until set.settings.size).map { index ->
                    WatchChannel(index, set.getChannel(index)?.name?.takeIf { it.isNotBlank() } ?: "#$index")
                }
            }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    // Direct-only at 2 s by default: that is what pointing a directional antenna needs.
    private val draftFlow = MutableStateFlow(SenderDraft())
    val draft: StateFlow<SenderDraft> = draftFlow.asStateFlow()

    fun setChannel(index: Int) {
        draftFlow.value = draftFlow.value.copy(channelIndex = index)
    }

    fun setInterval(seconds: Int) {
        draftFlow.value = draftFlow.value.copy(intervalSec = seconds)
    }

    fun setMode(mode: RangeTestBeaconMode) {
        draftFlow.value = draftFlow.value.copy(mode = mode)
    }

    fun startSending() {
        val d = draftFlow.value
        val channelName = channels.value.firstOrNull { it.index == d.channelIndex }?.name ?: "#${d.channelIndex}"
        viewModelScope.launch {
            sender.start(
                RangeTestSenderConfig(
                    channelIndex = d.channelIndex,
                    channelName = channelName,
                    intervalSec = d.intervalSec,
                    mode = d.mode,
                ),
            )
        }
    }

    fun stopSending() {
        viewModelScope.launch { sender.stop() }
    }

    fun dismissSender() = sender.dismiss()

    fun startRecording(nodeNum: Int) {
        val node = nodeRepository.nodeDBbyNum.value[nodeNum] ?: return
        viewModelScope.launch { recorder.start(node) }
    }

    fun stopRecording() {
        viewModelScope.launch { recorder.stop() }
    }
}

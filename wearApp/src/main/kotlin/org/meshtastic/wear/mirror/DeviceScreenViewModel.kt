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
package org.meshtastic.wear.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.AdminController
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.DisplayMirrorManager
import org.meshtastic.core.repository.MirrorFormat
import org.meshtastic.core.repository.MirrorFrame
import org.meshtastic.core.repository.MirrorPalette
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.DisplayInfo

/**
 * The radio's own screen on the wrist: the same display mirror the phone's Debug panel uses (framebuffer stream plus
 * injected input events), driven by the bezel and a few buttons. The stream runs only while the screen is visible.
 */
@KoinViewModel
class DeviceScreenViewModel(
    private val displayMirrorManager: DisplayMirrorManager,
    private val adminController: AdminController,
    connectionStateProvider: ConnectionStateProvider,
    nodeRepository: NodeRepository,
    radioConfigRepository: RadioConfigRepository,
) : ViewModel() {

    val frame: StateFlow<MirrorFrame?> = displayMirrorManager.frame
    val palette: StateFlow<MirrorPalette?> = displayMirrorManager.palette

    val connected: StateFlow<Boolean> =
        connectionStateProvider.connectionState
            .map { it is ConnectionState.Connected }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Panel description from the handshake; null on display-less radios and firmware too old to mirror. */
    val displayInfo: StateFlow<DisplayInfo?> =
        nodeRepository.ourNodeInfo.map { it?.metadata?.display }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Managed mode locks out admin writes, and arming the mirror and injecting keys are both admin writes. */
    private val managed: StateFlow<Boolean> =
        radioConfigRepository.localConfigFlow
            .map { it.security?.is_managed == true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val canMirror: StateFlow<Boolean> =
        combine(connected, displayInfo, managed) { up, info, locked -> up && info != null && !locked }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<DeviceScreenStatus> =
        combine(connected, displayInfo, managed) { up, info, locked ->
            when {
                !up -> DeviceScreenStatus.NOT_CONNECTED
                locked -> DeviceScreenStatus.MANAGED
                info == null -> DeviceScreenStatus.UNSUPPORTED
                else -> DeviceScreenStatus.READY
            }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DeviceScreenStatus.NOT_CONNECTED)

    /** Whether the screen is on and showing; the stream follows it so a dark watch does not keep pulling frames. */
    private var visible = false
    private var armed = false

    init {
        // Never show a previous radio's last screen as if it were live.
        displayMirrorManager.reset()
        viewModelScope.launch {
            canMirror.collect { ok ->
                if (!ok) {
                    // The radio forgets the (non-persisted) mirror flag on disconnect; follow it locally.
                    armed = false
                    if (!connected.value) displayMirrorManager.reset()
                }
                syncStream()
            }
        }
    }

    fun onVisible(isVisible: Boolean) {
        visible = isVisible
        syncStream()
    }

    private fun syncStream() {
        val want = visible && canMirror.value
        if (want == armed) return
        adminController.setDisplayMirror(want)
        armed = want
        // Arming only streams changes; ask for one frame so a static screen shows at once.
        if (want) adminController.requestDisplayFrame()
    }

    fun sendKey(event: Int) {
        if (managed.value || !connected.value) return
        adminController.sendInputEvent(event)
    }

    /**
     * Touch radios get the tap at its panel coordinates; MUI (the only RGB565 source) feeds taps to its own pointer
     * even without a touch panel. Everything else, like a plain OLED, treats the tap as its button.
     */
    private val tapsAsTouch: Boolean
        get() = displayInfo.value?.has_touch == true || frame.value?.format == MirrorFormat.RGB565

    /** A tap on the mirrored image at panel pixel ([x], [y]). */
    fun tap(x: Int, y: Int) = if (tapsAsTouch) sendTouch(DeviceInput.USER_PRESS, x, y) else sendKey(DeviceInput.SELECT)

    /** A long press on the mirrored image; touch drivers report it as SELECT with coordinates. */
    fun longPress(x: Int, y: Int) =
        if (tapsAsTouch) sendTouch(DeviceInput.SELECT, x, y) else sendKey(DeviceInput.SELECT_LONG)

    private fun sendTouch(event: Int, x: Int, y: Int) {
        if (managed.value || !connected.value) return
        adminController.sendInputEvent(event, touchX = x, touchY = y)
    }

    override fun onCleared() {
        if (armed) adminController.setDisplayMirror(false)
        armed = false
    }
}

enum class DeviceScreenStatus {
    READY,
    NOT_CONNECTED,
    UNSUPPORTED,
    MANAGED,
}

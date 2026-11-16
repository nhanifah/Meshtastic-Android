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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.ble.BleScanner
import org.meshtastic.core.ble.MeshtasticBleConstants
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.InterfaceId
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.feature.rangetest.RangeTestBeaconSender
import org.meshtastic.feature.rangetest.RangeTestRecorder
import org.meshtastic.wear.relay.PhoneLinkState
import org.meshtastic.wear.relay.WearPhoneRelayLink
import kotlin.time.Duration.Companion.seconds

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

/** How the watch reaches the radio. */
enum class LinkMode {
    NONE,
    PHONE,
    BLUETOOTH,
}

data class HomeState(
    val mode: LinkMode = LinkMode.NONE,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val phoneLink: PhoneLinkState = PhoneLinkState.IDLE,
    val ourNode: Node? = null,
    val onlineNodes: Int = 0,
    val unread: Int = 0,
    val rangeTestRunning: Boolean = false,
)

fun linkModeOf(address: String?): LinkMode = when (address?.firstOrNull()?.let(InterfaceId::forIdChar)) {
    InterfaceId.PHONE -> LinkMode.PHONE
    InterfaceId.BLUETOOTH -> LinkMode.BLUETOOTH
    else -> LinkMode.NONE
}

@KoinViewModel
class HomeViewModel(
    radioInterfaceService: RadioInterfaceService,
    radioController: RadioController,
    phoneRelayLink: WearPhoneRelayLink,
    nodeRepository: NodeRepository,
    packetRepository: PacketRepository,
    sender: RangeTestBeaconSender,
    recorder: RangeTestRecorder,
) : ViewModel() {
    private val partial =
        combine(
            radioInterfaceService.currentDeviceAddressFlow,
            radioInterfaceService.connectionState,
            phoneRelayLink.state,
            nodeRepository.ourNodeInfo,
        ) { address, connection, phone, ourNode ->
            HomeState(mode = linkModeOf(address), connection = connection, phoneLink = phone, ourNode = ourNode)
        }

    val state: StateFlow<HomeState> =
        combine(
            partial,
            nodeRepository.onlineNodeCount,
            packetRepository.getUnreadCountTotal(),
            sender.state,
            recorder.state,
        ) { base, online, unread, send, record ->
            base.copy(
                onlineNodes = online,
                unread = unread,
                rangeTestRunning = send.isSending || record.isRecording,
            )
        }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, HomeState())

    init {
        // A fresh install starts on the phone's radio, the setup most watches are bought for.
        if (radioInterfaceService.currentDeviceAddressFlow.value == null) {
            viewModelScope.launch { radioController.setDeviceAddress("${InterfaceId.PHONE.id}") }
        }
    }
}

/** One radio found by a Bluetooth scan. */
data class FoundRadio(val name: String, val address: String)

@KoinViewModel
class ConnectionViewModel(
    private val radioController: RadioController,
    private val radioInterfaceService: RadioInterfaceService,
    private val bleScanner: BleScanner,
) : ViewModel() {
    val mode: StateFlow<LinkMode> =
        combine(radioInterfaceService.currentDeviceAddressFlow, radioInterfaceService.connectionState) { a, _ ->
            linkModeOf(a)
        }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, LinkMode.NONE)

    val currentAddress: StateFlow<String?> = radioInterfaceService.currentDeviceAddressFlow

    private val found = MutableStateFlow<List<FoundRadio>>(emptyList())
    val radios: StateFlow<List<FoundRadio>> = found.asStateFlow()

    private val scanningFlow = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = scanningFlow.asStateFlow()

    private var scanJob: Job? = null

    fun usePhone() = select("${InterfaceId.PHONE.id}")

    fun useRadio(radio: FoundRadio) = select("${InterfaceId.BLUETOOTH.id}${radio.address}")

    fun disconnect() = select("${InterfaceId.NOP.id}")

    fun scan() {
        scanJob?.cancel()
        found.value = emptyList()
        scanningFlow.value = true
        scanJob = viewModelScope.launch {
            bleScanner
                .scan(timeout = SCAN_TIMEOUT, serviceUuid = MeshtasticBleConstants.SERVICE_UUID)
                .catch { scanningFlow.value = false }
                .collect { device ->
                    val radio = FoundRadio(device.name ?: device.address, device.address)
                    if (found.value.none { it.address == radio.address }) found.value = found.value + radio
                }
            scanningFlow.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanningFlow.value = false
    }

    private fun select(address: String) {
        stopScan()
        viewModelScope.launch { radioController.setDeviceAddress(address) }
    }

    private companion object {
        val SCAN_TIMEOUT = 20.seconds
    }
}

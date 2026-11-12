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
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed

/** What this phone's radio does in the test. */
enum class RangeTestRole {
    /** Stationary: sends the beacons. */
    SENDER,

    /** Mobile: listens and records signal and position. */
    RECEIVER,
}

enum class RangeTestWizardStep {
    ROLE,
    CHANNEL,
    SHARE,
    TARGET,
    BEACON,
    PICK_SENDER,
    LOCATION,
    SUMMARY,
}

enum class RangeTestWizardError {
    NOT_CONNECTED,
    NO_FREE_SLOT,
    INVALID_CODE,
    NO_CHANNEL,
    NO_SENDER,
    NO_DESTINATION,
    FAILED,
}

data class RangeTestWizardState(
    val role: RangeTestRole? = null,
    val step: RangeTestWizardStep = RangeTestWizardStep.ROLE,
    /** Sender: create (or reuse) the default `rangeTest` channel rather than pick an existing one. */
    val createChannel: Boolean = true,
    val channelIndex: Int? = null,
    val broadcast: Boolean = true,
    val destNum: Int? = null,
    val intervalSec: Int = RangeTestSenderConfig.DEFAULT_INTERVAL_SEC,
    val durationMin: Int? = DEFAULT_DURATION_MIN,
    val mode: RangeTestBeaconMode = RangeTestBeaconMode.MESSAGE,
    val senderNum: Int? = null,
    /** Filters the node lists (destination and sender pickers) by name, short name or node id. */
    val nodeQuery: String = "",
    val busy: Boolean = false,
    val error: RangeTestWizardError? = null,
) {
    val steps: List<RangeTestWizardStep>
        get() =
            when (role) {
                RangeTestRole.SENDER -> SENDER_STEPS
                RangeTestRole.RECEIVER -> RECEIVER_STEPS
                null -> listOf(RangeTestWizardStep.ROLE)
            }

    /** 1-based position for the "step x of y" header. */
    val stepNumber: Int
        get() = steps.indexOf(step) + 1

    val isFirstStep: Boolean
        get() = step == RangeTestWizardStep.ROLE

    companion object {
        const val DEFAULT_DURATION_MIN = 30
        private val SENDER_STEPS =
            listOf(
                RangeTestWizardStep.ROLE,
                RangeTestWizardStep.CHANNEL,
                RangeTestWizardStep.SHARE,
                RangeTestWizardStep.TARGET,
                RangeTestWizardStep.BEACON,
                RangeTestWizardStep.SUMMARY,
            )
        private val RECEIVER_STEPS =
            listOf(
                RangeTestWizardStep.ROLE,
                RangeTestWizardStep.CHANNEL,
                RangeTestWizardStep.PICK_SENDER,
                RangeTestWizardStep.LOCATION,
                RangeTestWizardStep.SUMMARY,
            )
    }
}

/** A node heard sending range-test beacons while the receiver wizard was open. */
data class RangeTestDetectedSender(
    val num: Int,
    val name: String,
    val lastHeardAt: Long,
    val snr: Float,
    val rssi: Int?,
    val hasPosition: Boolean,
)

/** Another node of the mesh, for picking a single beacon destination. */
data class RangeTestNodeOption(val num: Int, val name: String, val shortName: String) {
    /** Node id as shown elsewhere in the app, e.g. `!a1b2c3d4`. */
    val id: String
        get() = "!" + num.toUInt().toString(HEX_RADIX).padStart(NODE_ID_DIGITS, '0')

    /** True when [query] appears in the long name, short name or node id (case-insensitive). */
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() ||
            name.contains(q, ignoreCase = true) ||
            shortName.contains(q, ignoreCase = true) ||
            id.contains(q.removePrefix("!"), ignoreCase = true)
    }

    private companion object {
        const val HEX_RADIX = 16
        const val NODE_ID_DIGITS = 8
    }
}

private data class HeardBeacon(val at: Long, val snr: Float, val rssi: Int?)

/**
 * Drives the step-by-step setup: role, dedicated channel, then the sender's beacon settings or the receiver's choice of
 * sender. Starting hands over to [RangeTestBeaconSender] or [RangeTestRecorder] and emits the role on [started].
 */
@KoinViewModel
@Suppress("TooManyFunctions")
class RangeTestWizardViewModel(
    private val channelsRepo: RangeTestChannels,
    private val sender: RangeTestBeaconSender,
    private val recorder: RangeTestRecorder,
    private val nodeRepository: NodeRepository,
    private val serviceRepository: ServiceRepository,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(RangeTestWizardState())
    val state: StateFlow<RangeTestWizardState> = stateFlow.asStateFlow()

    private val startedChannel = Channel<RangeTestRole>(Channel.BUFFERED)
    val started: Flow<RangeTestRole> = startedChannel.receiveAsFlow()

    val channels: StateFlow<List<RangeTestChannelOption>> =
        channelsRepo.channels.stateInWhileSubscribed(initialValue = emptyList())

    /** The link the sender's QR code encodes: only the chosen test channel, added (not replaced) on scan. */
    val shareCode: StateFlow<String?> =
        combine(stateFlow, channels) { s, list ->
            list.firstOrNull { it.index == s.channelIndex }?.let { channelsRepo.shareCode(it.settings) }
        }
            .stateInWhileSubscribed(initialValue = null)

    val connected: StateFlow<Boolean> =
        combine(serviceRepository.connectionState, nodeRepository.myNodeInfo) { state, _ ->
            state == ConnectionState.Connected
        }
            .stateInWhileSubscribed(initialValue = false)

    /** Whether our own radio reports a GPS position (else the phone's GPS tags the readings). */
    val radioHasGps: StateFlow<Boolean> =
        combine(nodeRepository.ourNodeInfo, serviceRepository.connectionState) { node, _ ->
            node?.validPosition != null
        }
            .stateInWhileSubscribed(initialValue = false)

    val nodes: StateFlow<List<RangeTestNodeOption>> =
        combine(nodeRepository.nodeDBbyNum, nodeRepository.myNodeInfo) { nodes, me ->
            nodes.values
                .filter { it.num != me?.myNodeNum }
                .sortedByDescending { it.lastHeard }
                .map { RangeTestNodeOption(it.num, displayName(it), it.user.short_name) }
        }
            .stateInWhileSubscribed(initialValue = emptyList())

    private val heard = MutableStateFlow<Map<Int, HeardBeacon>>(emptyMap())

    val detectedSenders: StateFlow<List<RangeTestDetectedSender>> =
        combine(heard, nodeRepository.nodeDBbyNum) { beacons, nodes ->
            beacons
                .map { (num, beacon) ->
                    val node = nodes[num]
                    RangeTestDetectedSender(
                        num = num,
                        name = node?.let(::displayName) ?: num.toUInt().toString(HEX_RADIX),
                        lastHeardAt = beacon.at,
                        snr = beacon.snr,
                        rssi = beacon.rssi,
                        hasPosition = node?.validPosition != null,
                    )
                }
                .sortedByDescending { it.lastHeardAt }
        }
            .stateInWhileSubscribed(initialValue = emptyList())

    init {
        viewModelScope.launch {
            serviceRepository.meshPacketFlow.collect { packet ->
                if (RangeTestBeacon.parse(packet) == null) return@collect
                if (packet.from == nodeRepository.myNodeInfo.value?.myNodeNum) return@collect
                val beacon = HeardBeacon(nowMillis, packet.rx_snr, RangeTestSignal.rssiOrNull(packet))
                heard.update { it + (packet.from to beacon) }
                // Pre-select the first sender heard so the common case is one tap.
                stateFlow.update { if (it.senderNum == null) it.copy(senderNum = packet.from) else it }
            }
        }
    }

    fun selectRole(role: RangeTestRole) = stateFlow.update { it.copy(role = role, error = null) }

    fun setCreateChannel(create: Boolean) = stateFlow.update { it.copy(createChannel = create, error = null) }

    fun selectChannel(index: Int) = stateFlow.update {
        it.copy(channelIndex = index, createChannel = false, error = null)
    }

    fun setBroadcast(broadcast: Boolean) = stateFlow.update {
        it.copy(broadcast = broadcast)
    }

    fun selectDestination(num: Int) = stateFlow.update { it.copy(destNum = num, broadcast = false) }

    fun setInterval(seconds: Int) = stateFlow.update { it.copy(intervalSec = seconds) }

    fun setDuration(minutes: Int?) = stateFlow.update { it.copy(durationMin = minutes) }

    fun setMode(mode: RangeTestBeaconMode) = stateFlow.update { it.copy(mode = mode) }

    fun selectSender(num: Int) = stateFlow.update { it.copy(senderNum = num, error = null) }

    fun setNodeQuery(query: String) = stateFlow.update { it.copy(nodeQuery = query) }

    /** Joins the channel in a scanned or pasted code (receiver). */
    fun joinFromCode(code: String) = runBusy {
        val index = channelsRepo.joinFromCode(code)
        stateFlow.update { it.copy(channelIndex = index, createChannel = false) }
    }

    fun back() = stateFlow.update { s ->
        val i = s.steps.indexOf(s.step)
        if (i <= 0) s else s.copy(step = s.steps[i - 1], error = null)
    }

    fun next() {
        val s = stateFlow.value
        when {
            s.step == RangeTestWizardStep.CHANNEL && s.role == RangeTestRole.SENDER && s.createChannel ->
                runBusy {
                    val index = channelsRepo.ensureChannel()
                    stateFlow.update { it.copy(channelIndex = index) }
                    advance()
                }

            s.step == RangeTestWizardStep.SUMMARY -> start()

            else -> validate(s)?.let { error -> stateFlow.update { it.copy(error = error) } } ?: advance()
        }
    }

    private fun validate(s: RangeTestWizardState): RangeTestWizardError? = when (s.step) {
        RangeTestWizardStep.ROLE -> if (s.role == null) RangeTestWizardError.FAILED else null

        RangeTestWizardStep.CHANNEL -> if (s.channelIndex == null) RangeTestWizardError.NO_CHANNEL else null

        RangeTestWizardStep.TARGET ->
            if (!s.broadcast && s.destNum == null) RangeTestWizardError.NO_DESTINATION else null

        RangeTestWizardStep.PICK_SENDER -> if (s.senderNum == null) RangeTestWizardError.NO_SENDER else null

        else -> null
    }

    private fun advance() = stateFlow.update { s ->
        val i = s.steps.indexOf(s.step)
        s.copy(step = s.steps.getOrElse(i + 1) { s.step }, error = null)
    }

    private fun start() {
        val s = stateFlow.value
        when (s.role) {
            RangeTestRole.SENDER ->
                runBusy {
                    val index = s.channelIndex ?: throw RangeTestWizardException(RangeTestWizardError.NO_CHANNEL)
                    val channel = channels.value.firstOrNull { it.index == index }
                    val dest = if (s.broadcast) null else s.destNum
                    sender.start(
                        RangeTestSenderConfig(
                            channelIndex = index,
                            channelName = channel?.name.orEmpty(),
                            destNum = dest,
                            destName = dest?.let { num -> nodes.value.firstOrNull { it.num == num }?.name },
                            intervalSec = s.intervalSec,
                            durationMin = s.durationMin,
                            mode = s.mode,
                        ),
                    )
                    startedChannel.send(RangeTestRole.SENDER)
                }

            RangeTestRole.RECEIVER ->
                runBusy {
                    val node =
                        s.senderNum?.let { nodeRepository.nodeDBbyNum.value[it] }
                            ?: throw RangeTestWizardException(RangeTestWizardError.NO_SENDER)
                    recorder.start(node)
                    startedChannel.send(RangeTestRole.RECEIVER)
                }

            null -> Unit
        }
    }

    /** Runs [block] with the busy flag up, turning failures into a wizard error instead of a crash. */
    private fun runBusy(block: suspend () -> Unit) {
        if (stateFlow.value.busy) return
        stateFlow.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error =
                try {
                    if (serviceRepository.connectionState.value != ConnectionState.Connected) {
                        throw RangeTestWizardException(RangeTestWizardError.NOT_CONNECTED)
                    }
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RangeTestWizardException) {
                    e.error
                } catch (e: RangeTestChannelException) {
                    when (e.reason) {
                        RangeTestChannelException.Reason.NO_FREE_SLOT -> RangeTestWizardError.NO_FREE_SLOT
                        RangeTestChannelException.Reason.INVALID_CODE -> RangeTestWizardError.INVALID_CODE
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Logger.w(e) { "RangeTest wizard step failed" }
                    RangeTestWizardError.FAILED
                }
            stateFlow.update { it.copy(busy = false, error = error) }
        }
    }

    private class RangeTestWizardException(val error: RangeTestWizardError) : Exception(error.name)

    private companion object {
        const val HEX_RADIX = 16

        fun displayName(node: Node): String =
            node.user.long_name.ifBlank { node.user.short_name.ifBlank { node.user.id } }
    }
}

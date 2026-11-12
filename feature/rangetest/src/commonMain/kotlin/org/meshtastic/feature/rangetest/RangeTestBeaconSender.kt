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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing

/** How the stationary radio sends its beacons, as chosen in the wizard. */
data class RangeTestSenderConfig(
    val channelIndex: Int,
    val channelName: String,
    /** Null broadcasts to everyone on the channel; otherwise only this node is addressed. */
    val destNum: Int? = null,
    val destName: String? = null,
    val intervalSec: Int = DEFAULT_INTERVAL_SEC,
    /** Null runs until stopped. */
    val durationMin: Int? = null,
    /** Hop limit 0, so only radios in direct range hear the beacon. */
    val noRelay: Boolean = true,
    /** Ask the addressed node to confirm each beacon (specific destination only). */
    val wantAck: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTERVAL_SEC = 15
        const val MIN_INTERVAL_SEC = 10
        val INTERVALS_SEC = listOf(10, 15, 30, 60)
        val DURATIONS_MIN = listOf(15, 30, 60)
    }
}

data class RangeTestSenderState(
    val config: RangeTestSenderConfig? = null,
    val sent: Int = 0,
    val acked: Int = 0,
    val failed: Int = 0,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val lastSentAt: Long? = null,
    val finished: Boolean = false,
) {
    val isSending: Boolean
        get() = config != null && !finished
}

/**
 * Sends the app's own range-test beacons ([RangeTestBeacon]) from the connected radio on a timer: on the chosen
 * channel, to everyone or to one node, optionally without relays. The firmware Range Test module is not used.
 *
 * Application-scoped so sending survives leaving the screen; the phone must stay connected to the radio.
 */
@Single
class RangeTestBeaconSender(
    private val commandSender: CommandSender,
    private val serviceRepository: ServiceRepository,
    private val applicationScope: ApplicationCoroutineScope,
    dispatchers: CoroutineDispatchers,
) {
    private val mutex = Mutex()
    private val serial = dispatchers.default.limitedParallelism(1)
    private var job: Job? = null
    private val stateFlow = MutableStateFlow(RangeTestSenderState())
    val state: StateFlow<RangeTestSenderState> = stateFlow.asStateFlow()

    /** Packet ids of beacons still waiting for the addressed node's confirmation. */
    private val awaitingAck = LinkedHashSet<Int>()

    suspend fun start(config: RangeTestSenderConfig) = mutex.withLock {
        job?.cancel()
        awaitingAck.clear()
        val now = nowMillis
        val interval = config.intervalSec.coerceAtLeast(RangeTestSenderConfig.MIN_INTERVAL_SEC)
        val safeConfig = config.copy(intervalSec = interval, wantAck = config.wantAck && config.destNum != null)
        stateFlow.value =
            RangeTestSenderState(
                config = safeConfig,
                startedAt = now,
                endsAt = safeConfig.durationMin?.let { now + it * MILLIS_PER_MINUTE },
            )
        job =
            applicationScope.launch(serial) {
                if (safeConfig.wantAck) launch { collectAcks(safeConfig.destNum ?: return@launch) }
                sendLoop(safeConfig)
                stateFlow.update { it.copy(finished = true) }
            }
    }

    suspend fun stop() = mutex.withLock {
        job?.cancel()
        job = null
        stateFlow.update { if (it.config == null) it else it.copy(finished = true) }
    }

    /** Forgets a finished run so the hub no longer shows it. */
    fun dismiss() {
        if (!stateFlow.value.isSending) stateFlow.value = RangeTestSenderState()
    }

    private suspend fun sendLoop(config: RangeTestSenderConfig) {
        var seq = 1
        while (currentCoroutineContext().isActive) {
            val endsAt = stateFlow.value.endsAt
            if (endsAt != null && nowMillis >= endsAt) return
            val ok = sendOne(config, seq)
            stateFlow.update {
                if (ok) it.copy(sent = it.sent + 1, lastSentAt = nowMillis) else it.copy(failed = it.failed + 1)
            }
            seq++
            delay(config.intervalSec * MILLIS_PER_SECOND)
        }
    }

    private suspend fun sendOne(config: RangeTestSenderConfig, seq: Int): Boolean {
        if (serviceRepository.connectionState.value != ConnectionState.Connected) return false
        val id = commandSender.generatePacketId()
        val packet =
            DataPacket(
                to = config.destNum?.let { NodeAddress.numToDefaultId(it) } ?: NodeAddress.ID_BROADCAST,
                bytes = RangeTestBeacon.encode(seq, config.intervalSec),
                dataType = RangeTestBeacon.PORT.value,
                id = id,
                hopLimit = if (config.noRelay) DataPacket.HOP_LIMIT_NO_RELAY else 0,
                channel = config.channelIndex,
                wantAck = config.wantAck,
            )
        return try {
            if (config.wantAck) {
                awaitingAck.add(id)
                while (awaitingAck.size > MAX_PENDING_ACKS) awaitingAck.remove(awaitingAck.first())
            }
            commandSender.sendData(packet)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w(e) { "RangeTest beacon $seq not sent" }
            awaitingAck.remove(id)
            false
        }
    }

    private suspend fun collectAcks(destNum: Int) {
        serviceRepository.meshPacketFlow
            .filter { it.from == destNum && it.decoded?.portnum == PortNum.ROUTING_APP }
            .collect { packet ->
                val decoded = packet.decoded ?: return@collect
                if (!awaitingAck.remove(decoded.request_id)) return@collect
                val error = runCatching { Routing.ADAPTER.decode(decoded.payload).error_reason }.getOrNull()
                if (error == Routing.Error.NONE) stateFlow.update { it.copy(acked = it.acked + 1) }
            }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_PENDING_ACKS = 256
    }
}

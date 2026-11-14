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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.state.RadioOperation
import org.meshtastic.core.common.state.RadioOperationLock
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.dao.RangeTestDao
import org.meshtastic.core.database.entity.RangeTestSessionEntity
import org.meshtastic.core.database.entity.RangeTestSessionRole
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import kotlin.random.Random

/** How each beacon travels. */
enum class RangeTestBeaconMode {
    /**
     * Exactly like a chat message: relayed by other radios, and resent by the radio (up to three times) until delivery
     * is confirmed — so a received beacon means a message would have got through.
     */
    MESSAGE,

    /** Sent once with no relay: only a radio in direct range can hear it. Measures the raw link between two radios. */
    DIRECT,
}

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
    val mode: RangeTestBeaconMode = RangeTestBeaconMode.MESSAGE,
) {
    companion object {
        const val DEFAULT_INTERVAL_SEC = 15
        const val MIN_INTERVAL_SEC = 2

        /** Below this a beacon train takes up much of the channel's airtime; the wizard says so. */
        const val FAST_INTERVAL_SEC = 10
        val INTERVALS_SEC = listOf(2, 10, 15, 30, 60)
        val DURATIONS_MIN = listOf(15, 30, 60)
    }
}

data class RangeTestSenderState(
    val config: RangeTestSenderConfig? = null,
    val sessionId: Long? = null,
    val sent: Int = 0,
    /** Delivery confirmed: heard relayed, or acknowledged by the addressed node ([RangeTestBeaconMode.MESSAGE]). */
    val acked: Int = 0,
    /** The radio gave up on these after its retries without a confirmation ([RangeTestBeaconMode.MESSAGE]). */
    val unconfirmed: Int = 0,
    /** Could not be handed to the radio at all (not connected). */
    val failed: Int = 0,
    val lastSeq: Int = 0,
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
 * channel, to everyone or to one node, either like a chat message or direct-only ([RangeTestBeaconMode]). The firmware
 * Range Test module is not used. Each run is also kept in the range-test history, with its counts.
 *
 * Application-scoped so sending survives leaving the screen; the phone must stay connected to the radio.
 */
@Single
class RangeTestBeaconSender(
    private val commandSender: CommandSender,
    private val serviceRepository: ServiceRepository,
    private val rangeTestDao: RangeTestDao,
    private val radioOperationLock: RadioOperationLock,
    private val applicationScope: ApplicationCoroutineScope,
    dispatchers: CoroutineDispatchers,
) {
    private val mutex = Mutex()
    private val serial = dispatchers.default.limitedParallelism(1)
    private var job: Job? = null
    private var runId = ""

    /** The history entry of the current run until it is closed. */
    private var openSessionId: Long? = null
    private val stateFlow = MutableStateFlow(RangeTestSenderState())
    val state: StateFlow<RangeTestSenderState> = stateFlow.asStateFlow()

    /** Packet ids of beacons still waiting for a delivery confirmation. */
    private val awaitingAck = LinkedHashSet<Int>()

    suspend fun start(config: RangeTestSenderConfig) = mutex.withLock {
        job?.cancel()
        finishSession()
        awaitingAck.clear()
        val now = nowMillis
        val interval = config.intervalSec.coerceAtLeast(RangeTestSenderConfig.MIN_INTERVAL_SEC)
        val safeConfig = config.copy(intervalSec = interval)
        runId = Random.nextInt(1, Int.MAX_VALUE).toString(RUN_ID_RADIX)
        rangeTestDao.closeOpenSessionsOfRole(now, RangeTestSessionRole.SENDER)
        val sessionId =
            rangeTestDao.insertSession(
                RangeTestSessionEntity(
                    targetNodeNum = safeConfig.destNum ?: BROADCAST_NUM,
                    targetName = safeConfig.destName.orEmpty(),
                    startedAt = now,
                    role = RangeTestSessionRole.SENDER,
                    channelName = safeConfig.channelName,
                    intervalSec = interval,
                    beaconsSent = 0,
                    beaconsConfirmed = 0,
                    beaconsFailed = 0,
                ),
            )
        openSessionId = sessionId
        stateFlow.value =
            RangeTestSenderState(
                config = safeConfig,
                sessionId = sessionId,
                startedAt = now,
                endsAt = safeConfig.durationMin?.let { now + it * MILLIS_PER_MINUTE },
            )
        job =
            applicationScope.launch(serial) {
                // Keeps an Android phone's CPU awake with the screen off, or the beacon timer stalls.
                radioOperationLock.withOperation(RadioOperation.RangeTest) {
                    coroutineScope {
                        launch { saveCounts(sessionId) }
                        if (safeConfig.mode == RangeTestBeaconMode.MESSAGE) launch { collectAcks(safeConfig.destNum) }
                        sendLoop(safeConfig)
                        sendEnd(safeConfig)
                        stateFlow.update { it.copy(finished = true) }
                        // Give the last beacon's confirmation time to arrive before the run is closed.
                        delay(LAST_ACK_WAIT_MS)
                        mutex.withLock { finishSession() }
                        currentCoroutineContext().cancelChildren()
                    }
                }
            }
    }

    suspend fun stop() = mutex.withLock {
        val config = stateFlow.value.config
        job?.cancel()
        job = null
        if (config != null && !stateFlow.value.finished) {
            stateFlow.update { it.copy(finished = true) }
            applicationScope.launch(serial) { sendEnd(config) }
        }
        finishSession()
    }

    /** Marks sender runs left open by a process death as ended, unless one is actually running now. */
    suspend fun closeStaleSessions() = mutex.withLock {
        if (openSessionId == null) rangeTestDao.closeOpenSessionsOfRole(nowMillis, RangeTestSessionRole.SENDER)
    }

    /** Forgets a finished run so the hub no longer shows it. */
    fun dismiss() {
        if (!stateFlow.value.isSending) stateFlow.value = RangeTestSenderState()
    }

    private suspend fun finishSession() {
        val s = stateFlow.value
        val sessionId = openSessionId ?: return
        openSessionId = null
        rangeTestDao.updateSenderCounts(sessionId, s.sent, s.acked, s.failed + s.unconfirmed)
        rangeTestDao.endSession(sessionId, nowMillis)
    }

    private suspend fun saveCounts(sessionId: Long) {
        stateFlow
            .map { Triple(it.sent, it.acked, it.failed + it.unconfirmed) }
            .distinctUntilChanged()
            .collect { (sent, confirmed, failed) ->
                rangeTestDao.updateSenderCounts(sessionId, sent, confirmed, failed)
            }
    }

    private suspend fun sendLoop(config: RangeTestSenderConfig) {
        var seq = 1
        while (currentCoroutineContext().isActive) {
            val endsAt = stateFlow.value.endsAt
            val now = nowMillis
            if (endsAt != null && now >= endsAt) return
            val left = endsAt?.let { ((it - now) / MILLIS_PER_SECOND).toInt() }
            val ok = send(config, RangeTestBeacon.encode(seq, config.intervalSec, runId, left), wantAck = true)
            val sentSeq = seq
            stateFlow.update {
                if (ok) {
                    it.copy(sent = it.sent + 1, lastSeq = sentSeq, lastSentAt = nowMillis)
                } else {
                    it.copy(failed = it.failed + 1, lastSeq = sentSeq)
                }
            }
            seq++
            delay(config.intervalSec * MILLIS_PER_SECOND)
        }
    }

    /** Tells receivers the run is over, so they stop expecting (and counting missed) beacons. Best effort. */
    private suspend fun sendEnd(config: RangeTestSenderConfig) {
        val lastSeq = stateFlow.value.lastSeq
        if (lastSeq > 0) send(config, RangeTestBeacon.encodeEnd(lastSeq, runId), wantAck = false)
    }

    private suspend fun send(config: RangeTestSenderConfig, payload: ByteString, wantAck: Boolean): Boolean {
        if (serviceRepository.connectionState.value != ConnectionState.Connected) return false
        val id = commandSender.generatePacketId()
        val message = config.mode == RangeTestBeaconMode.MESSAGE
        val ack = wantAck && message
        val packet =
            DataPacket(
                to = config.destNum?.let { NodeAddress.numToDefaultId(it) } ?: NodeAddress.ID_BROADCAST,
                bytes = payload,
                dataType = RangeTestBeacon.PORT.value,
                id = id,
                // 0 lets the radio use its configured hop limit, as for a chat message.
                hopLimit = if (message) 0 else DataPacket.HOP_LIMIT_NO_RELAY,
                channel = config.channelIndex,
                wantAck = ack,
            )
        return try {
            if (ack) {
                awaitingAck.add(id)
                while (awaitingAck.size > MAX_PENDING_ACKS) awaitingAck.remove(awaitingAck.first())
            }
            commandSender.sendData(packet)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w(e) { "RangeTest beacon not sent" }
            awaitingAck.remove(id)
            false
        }
    }

    /**
     * Delivery reports for our beacons, matched by packet id. A broadcast is confirmed by our own radio once it hears
     * the beacon relayed (an implicit ack); a beacon to one node only by that node's acknowledgement.
     */
    private suspend fun collectAcks(destNum: Int?) {
        serviceRepository.meshPacketFlow
            .filter { it.decoded?.portnum == PortNum.ROUTING_APP }
            .collect { packet ->
                val decoded = packet.decoded ?: return@collect
                if (decoded.request_id !in awaitingAck) return@collect
                val error = runCatching { Routing.ADAPTER.decode(decoded.payload).error_reason }.getOrNull()
                when {
                    error == null -> Unit

                    error != Routing.Error.NONE -> {
                        awaitingAck.remove(decoded.request_id)
                        stateFlow.update { it.copy(unconfirmed = it.unconfirmed + 1) }
                    }

                    // A relay's implicit ack only says the beacon left; for one node, wait for that node's own ack.
                    destNum != null && packet.from != destNum -> Unit

                    else -> {
                        awaitingAck.remove(decoded.request_id)
                        stateFlow.update { it.copy(acked = it.acked + 1) }
                    }
                }
            }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_PENDING_ACKS = 256
        const val LAST_ACK_WAIT_MS = 30_000L
        const val RUN_ID_RADIX = 36

        /** 0xFFFFFFFF, the broadcast address, as the signed node number Room stores. */
        const val BROADCAST_NUM = -1
    }
}

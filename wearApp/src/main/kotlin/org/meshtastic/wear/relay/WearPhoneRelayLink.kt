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
package org.meshtastic.wear.relay

import android.app.Application
import co.touchlab.kermit.Logger
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.network.radio.PhoneRelayProtocol
import org.meshtastic.core.repository.PhoneRelayLink

/** Whether the paired phone is there to relay, as the watch UI shows it. */
enum class PhoneLinkState {
    /** Not relaying through the phone (direct Bluetooth, or nothing selected). */
    IDLE,

    /** Looking for the phone, or waiting for its first answer. */
    SEARCHING,

    /** No reachable phone with the Meshtastic app. */
    NO_PHONE,

    /** The phone answers but has no radio connected. */
    PHONE_WITHOUT_RADIO,

    /** Relaying. */
    READY,
}

/**
 * The watch end of the phone relay over the Wear Data Layer ([PhoneRelayProtocol]). While open it says hello to the
 * phone every [PhoneRelayProtocol.HELLO_INTERVAL_MILLIS]; the phone answers with its radio status and streams frames.
 * Messages from the phone arrive through [PhoneRelayListenerService].
 */
@Single(binds = [PhoneRelayLink::class, WearPhoneRelayLink::class])
class WearPhoneRelayLink(private val context: Application, private val scope: ApplicationCoroutineScope) :
    PhoneRelayLink {
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }

    private val stateFlow = MutableStateFlow(PhoneLinkState.IDLE)
    val state: StateFlow<PhoneLinkState> = stateFlow.asStateFlow()

    private val available = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = available.asStateFlow()

    // Bounded: the phone only streams while we say hello, and a config dump is a few hundred frames.
    private val channel = Channel<ByteArray>(FRAME_BUFFER, BufferOverflow.DROP_OLDEST)
    override val frames: Flow<ByteArray> = channel.receiveAsFlow()

    @Volatile private var phoneNodeId: String? = null

    @Volatile private var lastHeardMillis = 0L

    @Volatile private var opened = false
    private var helloJob: Job? = null

    @Synchronized
    override fun open() {
        if (opened) return
        opened = true
        // Drop frames left over from an earlier session.
        do {
            val drained = channel.tryReceive()
        } while (drained.isSuccess)
        stateFlow.value = PhoneLinkState.SEARCHING
        helloJob = scope.launch {
            while (isActive) {
                sayHello()
                delay(PhoneRelayProtocol.HELLO_INTERVAL_MILLIS)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!opened) return
        opened = false
        helloJob?.cancel()
        helloJob = null
        available.value = false
        stateFlow.value = PhoneLinkState.IDLE
        phoneNodeId?.let { id -> scope.launch { sendTo(id, PhoneRelayProtocol.PATH_BYE, ByteArray(0)) } }
    }

    override suspend fun send(bytes: ByteArray): Boolean {
        val id = phoneNodeId ?: findPhone() ?: return false
        return sendTo(id, PhoneRelayProtocol.PATH_TO_RADIO, bytes)
    }

    /** Called by [PhoneRelayListenerService] for every message from the phone. */
    fun onPhoneMessage(nodeId: String, path: String, data: ByteArray) {
        if (!opened) return
        phoneNodeId = nodeId
        lastHeardMillis = System.currentTimeMillis()
        when (path) {
            PhoneRelayProtocol.PATH_STATUS -> {
                val radioUp = data.firstOrNull() == 1.toByte()
                stateFlow.value = if (radioUp) PhoneLinkState.READY else PhoneLinkState.PHONE_WITHOUT_RADIO
                available.value = radioUp
            }

            PhoneRelayProtocol.PATH_FROM_RADIO -> channel.trySend(data)

            PhoneRelayProtocol.PATH_DUMP -> PhoneRelayProtocol.decodeBatch(data).forEach { channel.trySend(it) }

            else -> Logger.d { "Phone relay ignored path $path" }
        }
    }

    private suspend fun sayHello() {
        val id = findPhone()
        if (id == null) {
            phoneNodeId = null
            markUnreachable(PhoneLinkState.NO_PHONE)
            return
        }
        phoneNodeId = id
        val sent = sendTo(id, PhoneRelayProtocol.PATH_HELLO, ByteArray(0))
        val silentFor = System.currentTimeMillis() - lastHeardMillis
        if (!sent || (lastHeardMillis != 0L && silentFor > PhoneRelayProtocol.WATCH_TIMEOUT_MILLIS)) {
            markUnreachable(PhoneLinkState.NO_PHONE)
        }
    }

    private fun markUnreachable(state: PhoneLinkState) {
        available.value = false
        if (opened) stateFlow.value = state
    }

    private suspend fun findPhone(): String? = runCatching {
        val nodes =
            capabilityClient
                .getCapability(PhoneRelayProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }
        .onFailure { Logger.w { "Phone relay could not look for the phone: ${it.message}" } }
        .getOrNull()

    private suspend fun sendTo(nodeId: String, path: String, payload: ByteArray): Boolean = runCatching {
        messageClient.sendMessage(nodeId, path, payload).await()
    }
        .onFailure { Logger.w { "Phone relay send failed: ${it.message}" } }
        .isSuccess

    private companion object {
        const val FRAME_BUFFER = 4096
    }
}

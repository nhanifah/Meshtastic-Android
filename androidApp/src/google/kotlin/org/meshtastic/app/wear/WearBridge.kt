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
package org.meshtastic.app.wear

import android.app.Application
import co.touchlab.kermit.Logger
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.core.annotation.Single
import org.meshtastic.core.common.AppInstance
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.network.radio.PhoneRelayProtocol
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.webbridge.ToRadioDecision
import org.meshtastic.core.webbridge.ToRadioFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Lends the phone's radio session to paired Wear OS watches, with the same rules as Web Access: a watch may send mesh
 * packets, its config handshake is answered from the phone's copy, and anything that would disturb the phone's own
 * session is dropped ([ToRadioFilter]).
 *
 * Idle until a watch says hello; it relays only to watches heard from in the last
 * [PhoneRelayProtocol.WATCH_TIMEOUT_MILLIS], and stops once none are left.
 */
@Single
class WearBridge(
    private val context: Application,
    private val radio: RadioInterfaceService,
    private val configDumpProvider: ConfigDumpProvider,
    private val scope: ApplicationCoroutineScope,
) {
    private val messageClient by lazy { Wearable.getMessageClient(context) }

    /** Watch node id → when it was last heard from. */
    private val watches = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private var relayJob: Job? = null

    private val radioConnected: Boolean
        get() = radio.connectionState.value == ConnectionState.Connected

    fun onMessage(nodeId: String, path: String, data: ByteArray) {
        if (AppInstance.isClone) return
        when (path) {
            PhoneRelayProtocol.PATH_HELLO -> {
                touch(nodeId)
                scope.launch { send(nodeId, PhoneRelayProtocol.PATH_STATUS, statusPayload(radioConnected)) }
            }

            PhoneRelayProtocol.PATH_BYE -> watches.remove(nodeId)

            PhoneRelayProtocol.PATH_TO_RADIO -> {
                touch(nodeId)
                scope.launch { handleToRadio(nodeId, data) }
            }

            else -> Logger.d { "Wear bridge ignored path $path" }
        }
    }

    private fun touch(nodeId: String) {
        watches[nodeId] = System.currentTimeMillis()
        synchronized(lock) {
            if (relayJob?.isActive != true) relayJob = startRelay()
        }
    }

    private suspend fun handleToRadio(nodeId: String, bytes: ByteArray) {
        when (val decision = ToRadioFilter.decide(bytes)) {
            ToRadioDecision.Forward ->
                if (!radio.trySendToRadio(bytes)) Logger.w { "Wear bridge: no radio session for a watch packet" }

            is ToRadioDecision.AnswerConfig -> {
                val frames = configDumpProvider.configDump(decision.nonce)
                if (frames == null) {
                    Logger.i { "Wear bridge: no config to answer a watch handshake with yet" }
                } else {
                    send(
                        nodeId,
                        PhoneRelayProtocol.PATH_DUMP,
                        PhoneRelayProtocol.encodeBatch(frames.map { it.encode() }),
                    )
                }
            }

            is ToRadioDecision.Drop -> Logger.d { "Wear bridge dropped a watch frame (${decision.reason})" }
        }
    }

    private fun startRelay(): Job = scope.launch {
        Logger.i { "Wear bridge relaying to ${watches.size} watch(es)" }
        launch { radio.receivedDataMirror.collect { frame -> fanOut(frame.toByteArray()) } }
        launch {
            radio.connectionState
                .map { it == ConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    watches.keys.forEach { send(it, PhoneRelayProtocol.PATH_STATUS, statusPayload(connected)) }
                }
        }
        while (isActive) {
            delay(PhoneRelayProtocol.WATCH_TIMEOUT_MILLIS / 2)
            val cutoff = System.currentTimeMillis() - PhoneRelayProtocol.WATCH_TIMEOUT_MILLIS
            watches.entries.removeAll { it.value < cutoff }
            if (watches.isEmpty()) {
                Logger.i { "Wear bridge idle: no watch left" }
                synchronized(lock) { relayJob = null }
                coroutineContext[Job]?.cancel()
            }
        }
    }

    private suspend fun fanOut(frame: ByteArray) {
        watches.keys.forEach { send(it, PhoneRelayProtocol.PATH_FROM_RADIO, frame) }
    }

    private suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        runCatching { messageClient.sendMessage(nodeId, path, payload).await() }
            .onFailure { Logger.w { "Wear bridge could not reach watch: ${it.message}" } }
    }

    private fun statusPayload(connected: Boolean) = byteArrayOf(if (connected) 1 else 0)
}

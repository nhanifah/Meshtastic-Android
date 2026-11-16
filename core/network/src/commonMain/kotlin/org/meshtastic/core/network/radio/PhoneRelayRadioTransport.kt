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
package org.meshtastic.core.network.radio

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.meshtastic.core.repository.PhoneRelayLink
import org.meshtastic.core.repository.RadioTransport
import org.meshtastic.core.repository.RadioTransportCallback

/**
 * Reaches the radio through the paired phone ([PhoneRelayLink]). Connected while the phone says it has a live radio
 * session; frames flow both ways unchanged, and the phone answers the config handshake from its own copy.
 */
class PhoneRelayRadioTransport(
    private val callback: RadioTransportCallback,
    private val scope: CoroutineScope,
    private val link: PhoneRelayLink,
) : RadioTransport {
    private var job: Job? = null

    @kotlin.concurrent.Volatile private var connected = false

    override fun start() {
        link.open()
        job = scope.launch {
            link.isAvailable.collectLatest { available ->
                if (available) {
                    connected = true
                    callback.onConnect()
                    link.frames.collect { callback.handleFromRadio(it) }
                } else if (connected) {
                    connected = false
                    callback.onDisconnect(isPermanent = false, errorMessage = PHONE_UNREACHABLE)
                }
            }
        }
    }

    override fun handleSendToRadio(p: ByteArray): Boolean {
        if (!connected) return false
        scope.launch { if (!link.send(p)) Logger.w { "Phone relay could not deliver a ${p.size}-byte frame" } }
        return true
    }

    override suspend fun close() {
        job?.cancelAndJoin()
        job = null
        link.close()
        if (connected) {
            connected = false
            callback.onDisconnect(isPermanent = true)
        }
    }

    private companion object {
        const val PHONE_UNREACHABLE = "Phone not reachable"
    }
}

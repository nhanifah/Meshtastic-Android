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
package org.meshtastic.core.webbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioInterfaceService

/** A running bridge: where browsers reach it and how a person checks they reached the right phone. */
data class WebBridgeEndpoint(val host: String, val port: Int, val certificateSha256: String) {
    val origin: String
        get() = "https://$host:$port"
}

/** What the Web access screen shows. */
sealed interface WebBridgeState {
    data object Off : WebBridgeState

    data object Starting : WebBridgeState

    /**
     * Serving at [endpoint]. [pairingUrl] is what the QR code encodes; it carries a one-time token in its fragment and
     * is refreshed before the token expires. [hasWebClient] is false in builds that do not bundle the web client.
     */
    data class Running(
        val endpoint: WebBridgeEndpoint,
        val pairingUrl: String,
        val sessions: List<WebBridgeSessionInfo>,
        val hasWebClient: Boolean,
    ) : WebBridgeState {
        /** Never print the pairing token into logs. */
        override fun toString(): String = "Running(port=${endpoint.port}, sessions=${sessions.size})"
    }

    data class Failed(val reason: Reason) : WebBridgeState {
        enum class Reason {
            /** No local network address to listen on (Wi-Fi off, or only mobile data). */
            NO_NETWORK,

            /** The address or port could not be bound, or TLS setup failed. */
            START_FAILED,
        }
    }
}

/** Starts and stops the phone's Web access bridge. */
interface WebBridgeManager {
    /** False on platforms that cannot host it (iOS). */
    val isSupported: Boolean

    val state: StateFlow<WebBridgeState>

    fun start()

    fun stop()

    /** Issues a fresh pairing token, for example after a failed pairing or when showing the QR code again. */
    fun refreshPairingCode()

    /** Signs out the browser with session [id]. */
    fun revoke(id: String)
}

/**
 * Platform factory. [loadWebClientArchive] returns the bundled `build.tar`, or null when this build ships none; it is
 * read only while the bridge starts and is released when it stops.
 */
expect fun createWebBridgeManager(
    scope: CoroutineScope,
    radioInterfaceService: RadioInterfaceService,
    configDumpProvider: ConfigDumpProvider,
    dispatchers: CoroutineDispatchers,
    loadWebClientArchive: suspend () -> ByteArray?,
): WebBridgeManager

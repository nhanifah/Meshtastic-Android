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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioInterfaceService

/** iOS KMP stub so the shared module compiles; the iOS app does not host a web bridge. */
private object UnsupportedWebBridgeManager : WebBridgeManager {
    override val isSupported = false
    override val state: StateFlow<WebBridgeState> = MutableStateFlow<WebBridgeState>(WebBridgeState.Off).asStateFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override fun refreshPairingCode() = Unit

    override fun revoke(id: String) = Unit
}

actual fun createWebBridgeManager(
    scope: CoroutineScope,
    radioInterfaceService: RadioInterfaceService,
    configDumpProvider: ConfigDumpProvider,
    dispatchers: CoroutineDispatchers,
    loadWebClientArchive: suspend () -> ByteArray?,
): WebBridgeManager = UnsupportedWebBridgeManager

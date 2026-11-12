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
package org.meshtastic.core.webbridge.di

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.resources.Res
import org.meshtastic.core.webbridge.WebBridgeManager
import org.meshtastic.core.webbridge.createWebBridgeManager

@Module
class CoreWebBridgeModule {
    @Single
    fun provideWebBridgeManager(
        applicationScope: ApplicationCoroutineScope,
        radioInterfaceService: RadioInterfaceService,
        configDumpProvider: ConfigDumpProvider,
        dispatchers: CoroutineDispatchers,
    ): WebBridgeManager = createWebBridgeManager(
        scope = applicationScope,
        radioInterfaceService = radioInterfaceService,
        configDumpProvider = configDumpProvider,
        dispatchers = dispatchers,
        loadWebClientArchive = ::loadWebClientArchive,
    )

    /** The bridge still serves its API without a client, so a missing archive is logged, not fatal. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadWebClientArchive(): ByteArray? = try {
        Res.readBytes(WEB_CLIENT_ARCHIVE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w { "Web client archive not bundled: ${e::class.simpleName}" }
        null
    }

    private companion object {
        const val WEB_CLIENT_ARCHIVE = "files/webclient/build.tar"
    }
}

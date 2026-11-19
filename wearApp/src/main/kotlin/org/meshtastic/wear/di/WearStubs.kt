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
package org.meshtastic.wear.di

import org.koin.core.annotation.Single
import org.meshtastic.core.repository.AppWidgetUpdater
import org.meshtastic.core.repository.DataPair
import org.meshtastic.core.repository.MeshWorkerManager
import org.meshtastic.core.repository.MessageQueue
import org.meshtastic.core.repository.PersistedPacketId
import org.meshtastic.core.repository.PlatformAnalytics

/** The watch reports no analytics. */
@Single(binds = [PlatformAnalytics::class])
class WearPlatformAnalytics : PlatformAnalytics {
    override val isPlatformServicesAvailable: Boolean = false

    override fun track(event: String, vararg properties: DataPair) = Unit

    override fun setDeviceAttributes(firmwareVersion: String, model: String) = Unit
}

/** The watch has no home-screen widgets; its tile and complications refresh through WearSurfaceUpdater. */
@Single(binds = [AppWidgetUpdater::class])
class WearAppWidgetUpdater : AppWidgetUpdater {
    override suspend fun updateAll() = Unit
}

/**
 * Sends queued messages through WorkManager's SendMessageWorker, as the phone's WorkManagerMessageQueue does. That
 * class lives in :feature:messaging, which the watch does not include, so without this binding SendMessageUseCase (and
 * every conversation screen that injects it) failed to resolve.
 */
@Single(binds = [MessageQueue::class])
class WearMessageQueue(private val workerManager: MeshWorkerManager) : MessageQueue {
    override suspend fun enqueue(persistedId: PersistedPacketId) = workerManager.enqueueSendMessage(persistedId)
}

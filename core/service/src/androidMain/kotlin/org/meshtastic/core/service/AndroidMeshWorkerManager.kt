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
package org.meshtastic.core.service

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.common.AppInstance
import org.meshtastic.core.common.di.ServiceScope
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.repository.MeshWorkerManager
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PersistedPacketId
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.service.worker.SendMessageWorker

@Single
class AndroidMeshWorkerManager(
    private val workManager: Lazy<WorkManager>,
    private val packetRepository: PacketRepository,
    private val radioController: RadioController,
    private val scope: ServiceScope,
) : MeshWorkerManager {
    override fun enqueueSendMessage(persistedId: PersistedPacketId) {
        if (AppInstance.isClone) {
            sendInProcess(persistedId)
            return
        }
        val workRequest =
            OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setInputData(
                    workDataOf(
                        SendMessageWorker.KEY_PACKET_UUID to persistedId.uuid,
                        SendMessageWorker.KEY_MY_NODE_NUM to persistedId.myNodeNum,
                    ),
                )
                .build()

        // This UUID name does not replace a persisted legacy `send_message_<packetId>` job. Both can briefly coexist
        // after upgrade, but their transactional row claim allows only one send owner. KEEP also prevents repeated
        // scheduling of this exact row from cancelling an active worker after it has handed the packet to the radio.
        workManager.value.enqueueUniqueWork(
            "${SendMessageWorker.WORK_NAME_PREFIX}${persistedId.myNodeNum}_${persistedId.uuid}",
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
    }

    /**
     * WorkManager runs every job in the main process against the main app's database, so a clone sends in its own
     * process, as [SendMessageWorker] would. There is no retry: a packet that cannot go now stays QUEUED and is
     * enqueued again when the radio next finishes loading its config.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun sendInProcess(persistedId: PersistedPacketId) {
        scope.launch {
            if (radioController.connectionState.value != ConnectionState.Connected) return@launch
            val claimed = packetRepository.claimQueuedPacket(persistedId) ?: return@launch
            if (claimed.packet.status != MessageStatus.QUEUED) return@launch
            try {
                radioController.sendMessage(claimed.packet)
            } catch (e: Exception) {
                packetRepository.rollbackEnroutePacket(claimed.id)
                if (e is CancellationException) throw e
                Logger.w(e) { "Clone send failed; the packet stays queued" }
            }
        }
    }
}

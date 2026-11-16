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
package org.meshtastic.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.model.isBroadcast
import org.meshtastic.core.model.isFromLocal
import org.meshtastic.core.model.util.getChannel
import org.meshtastic.core.repository.ActiveConversationTracker
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.QuickChatActionRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.usecase.SendMessageUseCase

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

/** One conversation row on the watch. */
data class WatchContact(
    val key: String,
    val name: String,
    val isChannel: Boolean,
    val lastText: String?,
    val lastTime: Long,
    val unread: Int,
)

@KoinViewModel
class ContactsViewModel(
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    radioConfigRepository: RadioConfigRepository,
) : ViewModel() {
    val contacts: StateFlow<List<WatchContact>> =
        combine(
            packetRepository.getContacts(),
            radioConfigRepository.channelSetFlow,
            nodeRepository.myNodeInfo,
        ) { contacts, channelSet, myInfo ->
            val myNum = myInfo?.myNodeNum
            val channelKeys = (0 until channelSet.settings.size).map { ContactKey.broadcast(it).value }
            val keys = (channelKeys + contacts.keys).distinct().filterNot { ContactKey(it).isRetired }
            keys
                .map { key ->
                    val packet = contacts[key]
                    val channelIndex = ContactKey(key).channel
                    val isChannel = packet?.isBroadcast ?: true
                    val peerId = packet?.let { if (it.isFromLocal(myNum)) it.to else it.from }
                    val name =
                        if (isChannel) {
                            channelSet.getChannel(channelIndex)?.name?.takeIf { it.isNotBlank() }
                                ?: "#$channelIndex"
                        } else {
                            nodeRepository.getNode(peerId ?: NodeAddress.ID_BROADCAST).effectiveLongName
                        }
                    WatchContact(
                        key = key,
                        name = name,
                        isChannel = isChannel,
                        lastText = packet?.text,
                        lastTime = packet?.time ?: 0L,
                        unread = packetRepository.getUnreadCount(key),
                    )
                }
                .sortedWith(compareByDescending<WatchContact> { it.unread > 0 }.thenByDescending { it.lastTime })
        }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
}

@KoinViewModel
class ConversationViewModel(
    @InjectedParam private val contactKey: String,
    private val packetRepository: PacketRepository,
    private val nodeRepository: NodeRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val activeConversationTracker: ActiveConversationTracker,
    quickChatActionRepository: QuickChatActionRepository,
    radioConfigRepository: RadioConfigRepository,
) : ViewModel() {
    /** The channel name for a channel conversation, else the other node's name. */
    val title: StateFlow<String> =
        radioConfigRepository.channelSetFlow
            .map { set ->
                val key = ContactKey(contactKey)
                if (key.addressString == NodeAddress.ID_BROADCAST) {
                    set.getChannel(key.channel)?.name?.takeIf { it.isNotBlank() } ?: "#${key.channel}"
                } else {
                    nodeRepository.getNode(key.addressString).effectiveLongName
                }
            }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, "")

    /** Newest last, the last [MESSAGE_LIMIT] only: a watch shows a short tail of the conversation. */
    val messages: StateFlow<List<Message>> =
        packetRepository
            .getMessagesFrom(contactKey, limit = MESSAGE_LIMIT, includeFiltered = false) { userId ->
                nodeRepository.getNode(userId ?: NodeAddress.ID_BROADCAST)
            }
            .map { it.sortedBy { message -> message.receivedTime } }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    /** The user's own quick-chat texts from the phone app's settings, when this watch has any. */
    val quickChats: StateFlow<List<String>> =
        quickChatActionRepository
            .getAllActions()
            .map { actions -> actions.map { it.message }.filter { it.isNotBlank() } }
            .stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())

    fun onShown() = activeConversationTracker.setActive(contactKey)

    fun onHidden() = activeConversationTracker.clearActive(contactKey)

    fun markRead(latest: Message) {
        viewModelScope.launch {
            packetRepository.clearUnreadCount(contactKey, latest.receivedTime)
            packetRepository.updateLastReadMessage(contactKey, latest.uuid, latest.receivedTime)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { sendMessageUseCase(trimmed, contactKey) }
                .onFailure { Logger.w(it) { "Watch could not send a message" } }
        }
    }

    private companion object {
        const val MESSAGE_LIMIT = 40
    }
}

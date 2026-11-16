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

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.koin.core.context.GlobalContext

/** Receives the phone's relay messages and hands them to [WearPhoneRelayLink]. */
class PhoneRelayListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val link = GlobalContext.getOrNull()?.getOrNull<WearPhoneRelayLink>() ?: return
        link.onPhoneMessage(messageEvent.sourceNodeId, messageEvent.path, messageEvent.data)
    }
}

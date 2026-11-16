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

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.koin.core.context.GlobalContext

/** Wakes the app when a paired watch talks to it, and hands the message to [WearBridge]. */
class WearBridgeListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Koin is never started on a device that cannot load the bundled SQLite; there is no radio to lend then.
        val bridge = GlobalContext.getOrNull()?.getOrNull<WearBridge>() ?: return
        bridge.onMessage(messageEvent.sourceNodeId, messageEvent.path, messageEvent.data)
    }
}

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
package org.meshtastic.feature.voice

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.VoicePrefs

/**
 * Who this device speaks as: the connected radio's node number, or the last one seen, so voice over MQTT keeps working
 * while the radio is out of reach. 0 means no radio was ever connected; voice is then unavailable.
 */
@Single
class VoiceIdentity(nodeRepository: NodeRepository, private val prefs: VoicePrefs, scope: ApplicationCoroutineScope) {
    val nodeNum: StateFlow<Int> =
        combine(nodeRepository.myNodeInfo, prefs.lastNodeNum) { info, last ->
            val live = info?.myNodeNum ?: 0
            if (live != 0 && live != last) prefs.setLastNodeNum(live)
            if (live != 0) live else last
        }
            .stateIn(scope, SharingStarted.Eagerly, 0)
}

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
package org.meshtastic.core.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A watch's line to the radio its paired phone is connected to. The phone keeps its own radio session and relays raw
 * `FromRadio`/`ToRadio` frames, so the watch runs the normal mesh stack over it as if it were one more transport.
 *
 * Only the Wear OS app binds an implementation; everywhere else none is bound and `w` addresses are refused.
 */
interface PhoneRelayLink {
    /** True while the phone is reachable and has a live radio session to relay. */
    val isAvailable: StateFlow<Boolean>

    /** `FromRadio` frames relayed by the phone, in arrival order. Collected by one transport at a time. */
    val frames: Flow<ByteArray>

    /** Starts keeping the phone informed that this watch wants frames. Idempotent. */
    fun open()

    /** Stops asking the phone for frames. Idempotent. */
    fun close()

    /** Hands one `ToRadio` frame to the phone. Returns false when the phone could not be reached. */
    suspend fun send(bytes: ByteArray): Boolean
}

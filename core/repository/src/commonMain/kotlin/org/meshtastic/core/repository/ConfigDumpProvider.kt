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

import org.meshtastic.proto.FromRadio

/**
 * Answers a secondary client's `want_config_id` from the app's stored radio state, so the request never reaches the
 * radio. Firmware has no guard against a second `want_config` on a live session and crashes on it.
 */
interface ConfigDumpProvider {
    /**
     * Returns the `FromRadio` frames a radio would send for [nonce], ending with `config_complete_id = nonce`, or null
     * while the app has not finished its own handshake and has nothing truthful to answer with.
     */
    suspend fun configDump(nonce: Int): List<FromRadio>?
}

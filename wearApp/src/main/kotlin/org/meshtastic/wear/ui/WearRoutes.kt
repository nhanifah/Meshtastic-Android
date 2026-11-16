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

import android.net.Uri

/** Watch screens. Swiping right goes back. */
object WearRoutes {
    const val HOME = "home"
    const val CONNECTION = "connection"
    const val CONTACTS = "contacts"
    const val CONVERSATION = "conversation/{key}"
    const val NODES = "nodes"
    const val NODE = "node/{num}"
    const val RANGE_TEST = "rangetest"
    const val RANGE_TEST_PICK = "rangetest/pick"
    const val RANGE_TEST_SEND = "rangetest/send"
    const val RANGE_TEST_RECORD = "rangetest/record"

    fun conversation(key: String) = "conversation/${Uri.encode(key)}"

    fun node(num: Int) = "node/$num"
}

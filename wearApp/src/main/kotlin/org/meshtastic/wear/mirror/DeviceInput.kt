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
package org.meshtastic.wear.mirror

/**
 * Firmware `input_broker_event` codes (src/input/InputBroker.h), the same ones the phone's Display Mirror sends.
 * USER_PRESS is what touch drivers emit for a tap, with coordinates attached.
 */
object DeviceInput {
    const val SELECT = 10
    const val SELECT_LONG = 11
    const val UP = 17
    const val DOWN = 18
    const val LEFT = 19
    const val RIGHT = 20
    const val BACK = 27
    const val USER_PRESS = 28
}

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

import kotlinx.coroutines.flow.StateFlow

/**
 * The map's live tracker: a trail behind every node that moves, drawn on the main map.
 *
 * Modelled on how APRS trackers draw stations: a tail of a chosen length, a dot on every fix, a dashed leg where a node
 * jumped instead of travelling, and a short line ahead of a node that is moving. Off by default, because a trail per
 * node costs a query over the position log and draws over the chips.
 */
interface TrackerPrefs {
    val settings: StateFlow<TrackerSettings>

    /** Applies [transform] to the stored settings inside the store's own transaction. */
    fun update(transform: (TrackerSettings) -> TrackerSettings)
}

data class TrackerSettings(
    val enabled: Boolean = false,
    /** How far back each trail reaches, in minutes. One of [TAIL_MINUTES_OPTIONS]. */
    val tailMinutes: Int = DEFAULT_TAIL_MINUTES,
    /** A dot on every fix the trail passes through. */
    val showDots: Boolean = true,
    /** A short line ahead of a moving node, pointing where it is heading. */
    val showDirection: Boolean = true,
    /** Dashed legs where a node jumped (a long silence or an impossible speed) rather than travelled. */
    val showJumps: Boolean = true,
) {
    val tailSeconds: Long
        get() = tailMinutes * SECONDS_PER_MINUTE

    companion object {
        /** The tail lengths an APRS tracker offers: 5, 15 and 30 minutes, then 1 to 18 hours. */
        val TAIL_MINUTES_OPTIONS: List<Int> = listOf(5, 15, 30, 60, 120, 180, 360, 540, 720, 1080)

        const val DEFAULT_TAIL_MINUTES = 60

        private const val SECONDS_PER_MINUTE = 60L

        /** [minutes] if it is one of the offered lengths, otherwise the default, so a bad stored value cannot stick. */
        fun coerceTailMinutes(minutes: Int): Int =
            if (minutes in TAIL_MINUTES_OPTIONS) minutes else DEFAULT_TAIL_MINUTES
    }
}

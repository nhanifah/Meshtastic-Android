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
package org.meshtastic.feature.settings.tracker

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.repository.TrackerPrefs
import org.meshtastic.core.repository.TrackerSettings

@KoinViewModel
class TrackerSettingsViewModel(private val trackerPrefs: TrackerPrefs) : ViewModel() {
    val settings: StateFlow<TrackerSettings> = trackerPrefs.settings

    fun setEnabled(enabled: Boolean) = trackerPrefs.update { it.copy(enabled = enabled) }

    fun setTailMinutes(minutes: Int) = trackerPrefs.update {
        it.copy(tailMinutes = TrackerSettings.coerceTailMinutes(minutes))
    }

    fun setShowDots(show: Boolean) = trackerPrefs.update { it.copy(showDots = show) }

    fun setShowDirection(show: Boolean) = trackerPrefs.update { it.copy(showDirection = show) }

    fun setShowJumps(show: Boolean) = trackerPrefs.update { it.copy(showJumps = show) }
}

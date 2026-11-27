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
package org.meshtastic.core.prefs.tracker

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.UiDataStore
import org.meshtastic.core.repository.TrackerPrefs
import org.meshtastic.core.repository.TrackerSettings

@Single
class TrackerPrefsImpl(private val dataStore: UiDataStore, dispatchers: CoroutineDispatchers) : TrackerPrefs {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    override val settings: StateFlow<TrackerSettings> =
        dataStore.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Eagerly, TrackerSettings())

    override fun update(transform: (TrackerSettings) -> TrackerSettings) {
        scope.launch { dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) } }
    }

    private fun Preferences.toSettings() = TrackerSettings(
        enabled = this[KEY_ENABLED] ?: false,
        tailMinutes =
        TrackerSettings.coerceTailMinutes(this[KEY_TAIL_MINUTES] ?: TrackerSettings.DEFAULT_TAIL_MINUTES),
        showDots = this[KEY_SHOW_DOTS] ?: true,
        showDirection = this[KEY_SHOW_DIRECTION] ?: true,
        showJumps = this[KEY_SHOW_JUMPS] ?: true,
    )

    private fun MutablePreferences.write(settings: TrackerSettings) {
        this[KEY_ENABLED] = settings.enabled
        this[KEY_TAIL_MINUTES] = TrackerSettings.coerceTailMinutes(settings.tailMinutes)
        this[KEY_SHOW_DOTS] = settings.showDots
        this[KEY_SHOW_DIRECTION] = settings.showDirection
        this[KEY_SHOW_JUMPS] = settings.showJumps
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("tracker_enabled")
        private val KEY_TAIL_MINUTES = intPreferencesKey("tracker_tail_minutes")
        private val KEY_SHOW_DOTS = booleanPreferencesKey("tracker_show_dots")
        private val KEY_SHOW_DIRECTION = booleanPreferencesKey("tracker_show_direction")
        private val KEY_SHOW_JUMPS = booleanPreferencesKey("tracker_show_jumps")
    }
}

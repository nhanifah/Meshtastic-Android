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
package org.meshtastic.feature.voice.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.navigation.SettingsRoute
import org.meshtastic.core.navigation.VoiceRoute
import org.meshtastic.feature.voice.VoiceSettingsViewModel
import org.meshtastic.feature.voice.ui.VoiceSettingsScreen

/** Registers the voice screens into the Navigation 3 entry provider. */
fun EntryProviderScope<NavKey>.voiceGraph(backStack: NavBackStack<NavKey>) {
    entry<VoiceRoute.VoiceSettings> {
        VoiceSettingsScreen(
            viewModel = koinViewModel<VoiceSettingsViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            // The one place an MQTT server is entered; voice reads it from there.
            onOpenMqttSettings = { backStack.add(SettingsRoute.MQTT) },
        )
    }
}

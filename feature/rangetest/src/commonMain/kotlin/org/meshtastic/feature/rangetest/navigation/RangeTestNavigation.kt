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
package org.meshtastic.feature.rangetest.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.meshtastic.core.navigation.RangeTestRoute
import org.meshtastic.core.navigation.SettingsRoute
import org.meshtastic.feature.rangetest.RangeTestMapViewModel
import org.meshtastic.feature.rangetest.RangeTestViewModel
import org.meshtastic.feature.rangetest.ui.RangeTestMapScreen
import org.meshtastic.feature.rangetest.ui.RangeTestScreen

/** Registers the locked range-test screens into the Navigation 3 entry provider. */
fun EntryProviderScope<NavKey>.rangeTestGraph(backStack: NavBackStack<NavKey>) {
    entry<RangeTestRoute.RangeTestHome> {
        RangeTestScreen(
            viewModel = koinViewModel<RangeTestViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            onOpenMap = { sessionId -> backStack.add(RangeTestRoute.RangeTestMap(sessionId)) },
            onOpenModuleSettings = { backStack.add(SettingsRoute.RangeTest) },
        )
    }
    entry<RangeTestRoute.RangeTestMap> { route ->
        RangeTestMapScreen(
            viewModel = koinViewModel<RangeTestMapViewModel> { parametersOf(route.sessionId) },
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
}

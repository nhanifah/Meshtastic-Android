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
import org.meshtastic.feature.rangetest.RangeTestMapViewModel
import org.meshtastic.feature.rangetest.RangeTestRole
import org.meshtastic.feature.rangetest.RangeTestViewModel
import org.meshtastic.feature.rangetest.RangeTestWizardViewModel
import org.meshtastic.feature.rangetest.ui.RangeTestMapScreen
import org.meshtastic.feature.rangetest.ui.RangeTestReceiverScreen
import org.meshtastic.feature.rangetest.ui.RangeTestScreen
import org.meshtastic.feature.rangetest.ui.RangeTestSenderScreen
import org.meshtastic.feature.rangetest.ui.RangeTestWizardScreen

/** Registers the Range Test screens into the Navigation 3 entry provider. */
fun EntryProviderScope<NavKey>.rangeTestGraph(backStack: NavBackStack<NavKey>) {
    entry<RangeTestRoute.RangeTestHome> {
        RangeTestScreen(
            viewModel = koinViewModel<RangeTestViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            onStartWizard = { backStack.add(RangeTestRoute.RangeTestWizard) },
            onOpenSender = { backStack.add(RangeTestRoute.RangeTestSender) },
            onOpenReceiver = { backStack.add(RangeTestRoute.RangeTestReceiver) },
            onOpenMap = { sessionId -> backStack.add(RangeTestRoute.RangeTestMap(sessionId)) },
        )
    }
    entry<RangeTestRoute.RangeTestWizard> {
        RangeTestWizardScreen(
            viewModel = koinViewModel<RangeTestWizardViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            onStart = { role ->
                // The wizard is done: the live screen takes its place, so Back returns to the hub.
                backStack.removeLastOrNull()
                backStack.add(
                    when (role) {
                        RangeTestRole.SENDER -> RangeTestRoute.RangeTestSender
                        RangeTestRole.RECEIVER -> RangeTestRoute.RangeTestReceiver
                    },
                )
            },
        )
    }
    entry<RangeTestRoute.RangeTestSender> {
        RangeTestSenderScreen(
            viewModel = koinViewModel<RangeTestViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
    entry<RangeTestRoute.RangeTestReceiver> {
        RangeTestReceiverScreen(
            viewModel = koinViewModel<RangeTestViewModel>(),
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
            onOpenMap = { sessionId -> backStack.add(RangeTestRoute.RangeTestMap(sessionId)) },
            onStopRecording = { sessionId ->
                backStack.removeLastOrNull()
                backStack.add(RangeTestRoute.RangeTestMap(sessionId))
            },
        )
    }
    entry<RangeTestRoute.RangeTestMap> { route ->
        RangeTestMapScreen(
            viewModel = koinViewModel<RangeTestMapViewModel> { parametersOf(route.sessionId) },
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
}

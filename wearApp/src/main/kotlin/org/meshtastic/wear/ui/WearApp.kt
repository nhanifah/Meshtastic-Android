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
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.meshtastic.wear.mirror.DeviceScreen

@Composable
fun WearApp() {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = WearRoutes.HOME) {
                composable(WearRoutes.HOME) {
                    HomeScreen(
                        onConnection = { nav.navigate(WearRoutes.CONNECTION) },
                        onMessages = { nav.navigate(WearRoutes.CONTACTS) },
                        onNodes = { nav.navigate(WearRoutes.NODES) },
                        onRangeTest = { nav.navigate(WearRoutes.RANGE_TEST) },
                        onDeviceScreen = { nav.navigate(WearRoutes.DEVICE_SCREEN) },
                    )
                }
                composable(WearRoutes.CONNECTION) { ConnectionScreen(onDone = { nav.popBackStack() }) }
                composable(WearRoutes.CONTACTS) {
                    ContactsScreen(onOpen = { key -> nav.navigate(WearRoutes.conversation(key)) })
                }
                composable(WearRoutes.CONVERSATION) { entry ->
                    val key = entry.arguments?.getString("key")?.let(Uri::decode).orEmpty()
                    ConversationScreen(contactKey = key)
                }
                composable(WearRoutes.NODES) { NodesScreen(onOpen = { num -> nav.navigate(WearRoutes.node(num)) }) }
                composable(WearRoutes.NODE) { entry ->
                    val num = entry.arguments?.getString("num")?.toIntOrNull() ?: 0
                    NodeDetailScreen(nodeNum = num)
                }
                composable(WearRoutes.RANGE_TEST) {
                    RangeTestHomeScreen(
                        onSend = { nav.navigate(WearRoutes.RANGE_TEST_SEND) },
                        onRecord = { nav.navigate(WearRoutes.RANGE_TEST_PICK) },
                        onLiveRecording = { nav.navigate(WearRoutes.RANGE_TEST_RECORD) },
                    )
                }
                composable(WearRoutes.RANGE_TEST_SEND) { RangeTestSendScreen() }
                composable(WearRoutes.RANGE_TEST_PICK) {
                    RangeTestPickScreen(
                        onPick = {
                            nav.popBackStack()
                            nav.navigate(WearRoutes.RANGE_TEST_RECORD)
                        },
                    )
                }
                composable(WearRoutes.RANGE_TEST_RECORD) { RangeTestRecordScreen() }
                composable(WearRoutes.DEVICE_SCREEN) { DeviceScreen() }
            }
        }
    }
}

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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.effectiveLongName
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.connected
import org.meshtastic.core.resources.connected_sleeping
import org.meshtastic.core.resources.connecting
import org.meshtastic.core.resources.disconnected
import org.meshtastic.core.resources.grant_permission
import org.meshtastic.core.resources.messages
import org.meshtastic.core.resources.mirror_device_screen
import org.meshtastic.core.resources.no_device_selected
import org.meshtastic.core.resources.nodes
import org.meshtastic.core.resources.range_test
import org.meshtastic.core.resources.range_test_in_progress
import org.meshtastic.core.resources.wear_link_direct
import org.meshtastic.core.resources.wear_link_no_phone
import org.meshtastic.core.resources.wear_link_no_radio
import org.meshtastic.core.resources.wear_link_searching
import org.meshtastic.core.resources.wear_link_via_phone
import org.meshtastic.core.resources.wear_online_count
import org.meshtastic.core.resources.wear_permissions_needed
import org.meshtastic.core.resources.wear_unread_count
import org.meshtastic.wear.relay.PhoneLinkState

/** Everything the watch needs at runtime: Bluetooth for direct mode, location for distance and range test. */
fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}
    .toTypedArray()

fun hasAllPermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun HomeScreen(
    onConnection: () -> Unit,
    onMessages: () -> Unit,
    onNodes: () -> Unit,
    onRangeTest: () -> Unit,
    onDeviceScreen: () -> Unit,
) {
    val viewModel: HomeViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasAllPermissions(context)
        }

    ListScreen(title = state.ourNode?.effectiveLongName?.takeIf { it.isNotBlank() } ?: "Meshtastic") {
        if (!granted) {
            item {
                RowButton(
                    label = stringResource(Res.string.grant_permission),
                    secondary = stringResource(Res.string.wear_permissions_needed),
                    selected = true,
                    onClick = { permissionLauncher.launch(requiredPermissions()) },
                )
            }
        }
        item { RowButton(label = linkTitle(state), secondary = linkDetail(state), onClick = onConnection) }
        item {
            RowButton(
                label = stringResource(Res.string.messages),
                secondary = state.unread.takeIf { it > 0 }?.let { stringResource(Res.string.wear_unread_count, it) },
                selected = state.unread > 0,
                onClick = onMessages,
            )
        }
        item {
            RowButton(
                label = stringResource(Res.string.nodes),
                secondary = stringResource(Res.string.wear_online_count, state.onlineNodes),
                onClick = onNodes,
            )
        }
        item {
            RowButton(
                label = stringResource(Res.string.range_test),
                secondary = if (state.rangeTestRunning) stringResource(Res.string.range_test_in_progress) else null,
                selected = state.rangeTestRunning,
                onClick = onRangeTest,
            )
        }
        item { RowButton(label = stringResource(Res.string.mirror_device_screen), onClick = onDeviceScreen) }
    }
}

@Composable
private fun linkTitle(state: HomeState): String = when (state.mode) {
    LinkMode.PHONE -> stringResource(Res.string.wear_link_via_phone)
    LinkMode.BLUETOOTH -> stringResource(Res.string.wear_link_direct)
    LinkMode.NONE -> stringResource(Res.string.no_device_selected)
}

@Composable
private fun linkDetail(state: HomeState): String? {
    val phoneProblem =
        when (state.phoneLink) {
            PhoneLinkState.SEARCHING -> Res.string.wear_link_searching

            PhoneLinkState.NO_PHONE -> Res.string.wear_link_no_phone

            PhoneLinkState.PHONE_WITHOUT_RADIO -> Res.string.wear_link_no_radio

            PhoneLinkState.IDLE,
            PhoneLinkState.READY,
            -> null
        }
    val text =
        when {
            state.mode == LinkMode.NONE -> null

            state.mode == LinkMode.PHONE && state.connection != ConnectionState.Connected && phoneProblem != null ->
                phoneProblem

            else ->
                when (state.connection) {
                    ConnectionState.Connected -> Res.string.connected
                    ConnectionState.Connecting -> Res.string.connecting
                    ConnectionState.DeviceSleep -> Res.string.connected_sleeping
                    ConnectionState.Disconnected -> Res.string.disconnected
                }
        }
    return text?.let { stringResource(it) }
}

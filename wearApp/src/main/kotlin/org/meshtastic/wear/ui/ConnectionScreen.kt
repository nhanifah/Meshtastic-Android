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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.model.InterfaceId
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.bluetooth_available_devices
import org.meshtastic.core.resources.no_bluetooth_devices_seen
import org.meshtastic.core.resources.wear_link_choose
import org.meshtastic.core.resources.wear_link_direct_desc
import org.meshtastic.core.resources.wear_link_disconnect
import org.meshtastic.core.resources.wear_link_via_phone
import org.meshtastic.core.resources.wear_link_via_phone_desc
import org.meshtastic.core.resources.wear_scan_radios
import org.meshtastic.core.resources.wear_scanning

@Composable
fun ConnectionScreen(onDone: () -> Unit) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val address by viewModel.currentAddress.collectAsStateWithLifecycle()
    val radios by viewModel.radios.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    ListScreen(title = stringResource(Res.string.wear_link_choose)) {
        item {
            RowButton(
                label = stringResource(Res.string.wear_link_via_phone),
                secondary = stringResource(Res.string.wear_link_via_phone_desc),
                selected = mode == LinkMode.PHONE,
                onClick = {
                    viewModel.usePhone()
                    onDone()
                },
            )
        }
        item {
            RowButton(
                label = stringResource(if (scanning) Res.string.wear_scanning else Res.string.wear_scan_radios),
                secondary = stringResource(Res.string.wear_link_direct_desc),
                selected = mode == LinkMode.BLUETOOTH,
                enabled = !scanning,
                onClick = viewModel::scan,
            )
        }
        if (radios.isNotEmpty()) {
            item { InfoText(stringResource(Res.string.bluetooth_available_devices)) }
        } else if (!scanning) {
            item { InfoText(stringResource(Res.string.no_bluetooth_devices_seen)) }
        }
        radios.forEach { radio ->
            item(key = radio.address) {
                RowButton(
                    label = radio.name,
                    secondary = radio.address,
                    selected = address == "${InterfaceId.BLUETOOTH.id}${radio.address}",
                    onClick = {
                        viewModel.useRadio(radio)
                        onDone()
                    },
                )
            }
        }
        if (mode != LinkMode.NONE) {
            item {
                RowButton(
                    label = stringResource(Res.string.wear_link_disconnect),
                    onClick = {
                        viewModel.disconnect()
                        onDone()
                    },
                )
            }
        }
    }
}

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
package org.meshtastic.feature.settings.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_update_entry_available
import org.meshtastic.core.resources.app_update_entry_desc
import org.meshtastic.core.resources.app_update_title
import org.meshtastic.core.resources.range_test_entry_desc
import org.meshtastic.core.resources.range_test_locked_title
import org.meshtastic.core.ui.icon.ChevronRight
import org.meshtastic.core.ui.icon.Distance
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.SystemUpdate
import org.meshtastic.feature.settings.appupdate.AppUpdateManager
import org.meshtastic.feature.settings.appupdate.AppUpdateState

/** The dedicated Range Test entry: a prominent card at the top of Settings, separate from the radio's own modules. */
@Composable
internal fun RangeTestEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MeshtasticIcons.Distance, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(Res.string.range_test_locked_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.range_test_entry_desc), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(MeshtasticIcons.ChevronRight, contentDescription = null)
        }
    }
}

/** Opens the App update screen; says when a newer build is waiting. Checks quietly at most every few hours. */
@Composable
internal fun AppUpdateEntryCard(onClick: () -> Unit) {
    val manager = koinInject<AppUpdateManager>()
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager) { manager.checkIfStale() }
    val available = (state as? AppUpdateState.Available)?.release
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (available != null) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MeshtasticIcons.SystemUpdate, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(Res.string.app_update_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (available != null) {
                        stringResource(Res.string.app_update_entry_available, available.title)
                    } else {
                        stringResource(Res.string.app_update_entry_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(MeshtasticIcons.ChevronRight, contentDescription = null)
        }
    }
}

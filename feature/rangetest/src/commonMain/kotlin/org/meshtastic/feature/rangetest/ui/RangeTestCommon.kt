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
package org.meshtastic.feature.rangetest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.database.entity.RangeTestLocationSource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.range_test_grant
import org.meshtastic.core.resources.range_test_location_permission
import org.meshtastic.core.resources.range_test_location_phone
import org.meshtastic.core.resources.range_test_location_radio
import org.meshtastic.core.resources.range_test_waiting_fix
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberLocationPermissionState
import org.meshtastic.feature.rangetest.RangeTestFix

private const val TICK_MS = 1_000L

/** Title plus a back arrow, shared by the range-test screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopBar(title: String, onNavigateUp: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        },
        actions = actions,
    )
}

/** A big number over a small label. */
@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/** A row of [Stat]s sharing the width evenly, so the layout holds on narrow phones and wide tablets alike. */
@Composable
internal fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        stats.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f)) }
    }
}

/** A tinted card with a leading icon, a title and free content: the building block of the hub and wizard. */
@Composable
internal fun InfoCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** Which GPS tags the readings, and a way to grant the phone's location when it is missing. */
@Composable
internal fun LocationStatus(fix: RangeTestFix?) {
    val permission = rememberLocationPermissionState()
    Column {
        Text(
            when {
                fix == null -> stringResource(Res.string.range_test_waiting_fix)
                fix.source == RangeTestLocationSource.RADIO -> stringResource(Res.string.range_test_location_radio)
                else -> stringResource(Res.string.range_test_location_phone)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        LocationPermissionPrompt(permission.status, permission.request, permission.openAppSettings)
    }
}

@Composable
internal fun LocationPermissionPrompt(status: PermissionStatus, request: () -> Unit, openSettings: () -> Unit) {
    if (status == PermissionStatus.GRANTED) return
    Column {
        Text(
            stringResource(Res.string.range_test_location_permission),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = if (status == PermissionStatus.PERMANENTLY_DENIED) openSettings else request) {
            Text(stringResource(Res.string.range_test_grant))
        }
    }
}

/** The current time, ticking once a second while shown, for "x s ago" and countdowns. */
@Composable
internal fun rememberTickingNow(): State<Long> {
    val now = remember { mutableLongStateOf(nowMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now.longValue = nowMillis
        }
    }
    return now
}

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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.meshtastic.core.common.util.nowMillis

/** A scrolling watch screen with a header; [content] adds the rows. */
@Composable
fun ListScreen(title: String, modifier: Modifier = Modifier, content: TransformingLazyColumnScope.() -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(title, textAlign = TextAlign.Center) } }
            content()
        }
    }
}

/** A full-width row button with an optional second line. */
@Composable
fun RowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

/** A line of plain text in a list, centred. */
@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** "12s", "5m", "3h", "2d" since [epochMillis]; empty for an unknown time. */
fun agoShort(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val seconds = ((nowMillis - epochMillis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
        else -> "${seconds / SECONDS_PER_DAY}d"
    }
}

/** "1.2 km" / "350 m", or the imperial equivalents. */
fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
    val feet = meters * FEET_PER_METER
    if (feet < FEET_PER_MILE / TENTHS) "${feet.toInt()} ft" else "${oneDecimal(feet / FEET_PER_MILE)} mi"
} else {
    if (meters < METERS_PER_KM) "${meters.toInt()} m" else "${oneDecimal(meters / METERS_PER_KM)} km"
}

fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * TENTHS).toLong()
    return "${tenths / TENTHS.toLong()}.${kotlin.math.abs(tenths % TENTHS.toLong())}"
}

fun oneDecimal(value: Float): String = oneDecimal(value.toDouble())

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
private const val FEET_PER_METER = 3.28084
private const val FEET_PER_MILE = 5280.0
private const val METERS_PER_KM = 1000.0
private const val TENTHS = 10.0

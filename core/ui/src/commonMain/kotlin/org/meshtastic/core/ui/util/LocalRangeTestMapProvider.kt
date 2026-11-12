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
package org.meshtastic.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import org.meshtastic.core.ui.component.PlaceholderScreen

/**
 * Provides the range-test map: the signal heatmap draped over the base map, the receiver's route and the sender and
 * receiver pins, all described by a platform-neutral [RangeTestMapData]. Google Maps backs it on the google flavor,
 * MapLibre on fdroid and desktop.
 */
@Suppress("Wrapping", "CompositionLocalAllowlist")
val LocalRangeTestMapProvider =
    compositionLocalOf<@Composable (data: RangeTestMapData, modifier: Modifier) -> Unit> {
        { _, _ -> PlaceholderScreen("Range Test Map") }
    }

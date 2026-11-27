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
package org.meshtastic.feature.settings.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.repository.TrackerSettings
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.duration_hours_short
import org.meshtastic.core.resources.duration_minutes_short
import org.meshtastic.core.resources.tracker_enabled
import org.meshtastic.core.resources.tracker_enabled_summary
import org.meshtastic.core.resources.tracker_show_direction
import org.meshtastic.core.resources.tracker_show_direction_summary
import org.meshtastic.core.resources.tracker_show_dots
import org.meshtastic.core.resources.tracker_show_jumps
import org.meshtastic.core.resources.tracker_show_jumps_summary
import org.meshtastic.core.resources.tracker_tail_length
import org.meshtastic.core.resources.tracker_title
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.feature.settings.component.ExpressiveSection
import kotlin.math.roundToInt

/** Settings for the map's live tracker: the master switch, the tail length and what each trail draws. */
@Composable
fun TrackerSettingsScreen(
    viewModel: TrackerSettingsViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.tracker_title),
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                ourNode = null,
                showNodeChip = false,
                actions = {},
                onClickChip = {},
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TrackerSettingsContent(
                settings = settings,
                onEnabledChange = viewModel::setEnabled,
                onTailMinutesChange = viewModel::setTailMinutes,
                onShowDotsChange = viewModel::setShowDots,
                onShowDirectionChange = viewModel::setShowDirection,
                onShowJumpsChange = viewModel::setShowJumps,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun TrackerSettingsContent(
    settings: TrackerSettings,
    onEnabledChange: (Boolean) -> Unit,
    onTailMinutesChange: (Int) -> Unit,
    onShowDotsChange: (Boolean) -> Unit,
    onShowDirectionChange: (Boolean) -> Unit,
    onShowJumpsChange: (Boolean) -> Unit,
) {
    ExpressiveSection(title = stringResource(Res.string.tracker_title)) {
        SwitchPreference(
            title = stringResource(Res.string.tracker_enabled),
            summary = stringResource(Res.string.tracker_enabled_summary),
            checked = settings.enabled,
            enabled = true,
            onCheckedChange = onEnabledChange,
        )

        // Every option below stays visible but disabled while the tracker is off, so the user can see what it does.
        TailLengthSlider(
            tailMinutes = settings.tailMinutes,
            enabled = settings.enabled,
            onTailMinutesChange = onTailMinutesChange,
        )
        SwitchPreference(
            title = stringResource(Res.string.tracker_show_dots),
            checked = settings.showDots,
            enabled = settings.enabled,
            onCheckedChange = onShowDotsChange,
        )
        SwitchPreference(
            title = stringResource(Res.string.tracker_show_direction),
            summary = stringResource(Res.string.tracker_show_direction_summary),
            checked = settings.showDirection,
            enabled = settings.enabled,
            onCheckedChange = onShowDirectionChange,
        )
        SwitchPreference(
            title = stringResource(Res.string.tracker_show_jumps),
            summary = stringResource(Res.string.tracker_show_jumps_summary),
            checked = settings.showJumps,
            enabled = settings.enabled,
            onCheckedChange = onShowJumpsChange,
        )
    }
}

/** The tail length, as a slider over [TrackerSettings.TAIL_MINUTES_OPTIONS], labelled with the chosen length. */
@Composable
private fun TailLengthSlider(tailMinutes: Int, enabled: Boolean, onTailMinutesChange: (Int) -> Unit) {
    val options = TrackerSettings.TAIL_MINUTES_OPTIONS
    val selectedIndex = options.indexOf(tailMinutes).coerceAtLeast(0)
    var sliderPosition by remember(selectedIndex) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val shownMinutes = options[sliderPosition.roundToInt().coerceIn(0, options.lastIndex)]

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(Res.string.tracker_tail_length, tailLabel(shownMinutes)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
        )
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                onTailMinutesChange(options[sliderPosition.roundToInt().coerceIn(0, options.lastIndex)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            enabled = enabled,
        )
    }
}

@Composable
private fun tailLabel(minutes: Int): String = if (minutes < MINUTES_PER_HOUR) {
    stringResource(Res.string.duration_minutes_short, minutes)
} else {
    stringResource(Res.string.duration_hours_short, minutes / MINUTES_PER_HOUR)
}

private const val MINUTES_PER_HOUR = 60

/** Material's disabled-content alpha, matching the disabled switches beside it. */
private const val DISABLED_CONTENT_ALPHA = 0.38f

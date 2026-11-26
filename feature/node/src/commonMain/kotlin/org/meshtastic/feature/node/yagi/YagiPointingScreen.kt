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
package org.meshtastic.feature.node.yagi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.compass_location_disabled
import org.meshtastic.core.resources.compass_no_location_fix
import org.meshtastic.core.resources.compass_no_location_permission
import org.meshtastic.core.resources.compass_no_magnetometer
import org.meshtastic.core.resources.grant_permission
import org.meshtastic.core.resources.open_settings
import org.meshtastic.core.resources.yagi_how_to
import org.meshtastic.core.resources.yagi_no_target_position
import org.meshtastic.core.resources.yagi_on_target
import org.meshtastic.core.resources.yagi_pointing_title
import org.meshtastic.core.resources.yagi_position_from_radio
import org.meshtastic.core.resources.yagi_position_manual
import org.meshtastic.core.resources.yagi_tilt_down
import org.meshtastic.core.resources.yagi_tilt_up
import org.meshtastic.core.resources.yagi_turn_left
import org.meshtastic.core.resources.yagi_turn_right
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberLocationPermissionState
import org.meshtastic.core.ui.util.rememberOpenLocationSettings
import org.meshtastic.feature.node.compass.CompassWarning
import kotlin.math.abs

private val NearColor = Color(0xFFF9A825)
private const val NEAR_AZIMUTH_DEG = 15.0
private const val MAX_CONTENT_WIDTH_DP = 560
private const val DIAL_WIDTH_FRACTION = 0.8f

@Composable
fun YagiPointingScreen(viewModel: YagiPointingViewModel, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.yagi_pointing_title),
                subtitle = state.targetName.ifBlank { null },
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = true,
                onNavigateUp = onNavigateUp,
                onClickChip = {},
                actions = {},
            )
        },
    ) { padding ->
        YagiPointingContent(
            state = state,
            actions = viewModel.inputs,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

@Composable
private fun YagiPointingContent(state: YagiPointingUiState, actions: YagiInputActions, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.isOnTarget) {
        if (state.isOnTarget) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH_DP.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            YagiWarnings(state)
            GuidanceBanner(state)
            YagiDial(
                heading = state.heading,
                azimuth = state.azimuthTrue,
                onTarget = state.isAzimuthAligned,
                modifier = Modifier.fillMaxWidth(DIAL_WIDTH_FRACTION).aspectRatio(1f),
            )
            TiltGauge(
                boom = state.boomElevation,
                target = state.targetElevation,
                onTarget = state.isElevationAligned,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Text(
                text = stringResource(Res.string.yagi_how_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ReadoutCard(state)
            FarEndCard(state)
            SensorStatusCard(state, actions)
            ManualInputCard(state, actions)
        }
    }
}

@Composable
private fun YagiWarnings(state: YagiPointingUiState) {
    val permission = rememberLocationPermissionState()
    val openLocationSettings = rememberOpenLocationSettings()
    val notes = buildList {
        if (!state.hasTargetPosition) add(stringResource(Res.string.yagi_no_target_position))
        when (state.ownPositionSource) {
            OwnPositionSource.RADIO -> add(stringResource(Res.string.yagi_position_from_radio))
            OwnPositionSource.MANUAL -> add(stringResource(Res.string.yagi_position_manual))
            else -> Unit
        }
    }
    val visibleWarnings =
        state.warnings.filterNot {
            // With the radio's position standing in, a missing phone fix is already explained above.
            state.ownPositionSource == OwnPositionSource.RADIO && it == CompassWarning.NO_LOCATION_FIX
        }
    if (notes.isEmpty() && visibleWarnings.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.forEach { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
            visibleWarnings.forEach { warning ->
                Text(warningText(warning), color = MaterialTheme.colorScheme.onErrorContainer)
                when (warning) {
                    CompassWarning.NO_LOCATION_PERMISSION ->
                        FilledTonalButton(
                            onClick = {
                                if (permission.status == PermissionStatus.PERMANENTLY_DENIED) {
                                    permission.openAppSettings()
                                } else {
                                    permission.request()
                                }
                            },
                        ) {
                            Text(stringResource(Res.string.grant_permission))
                        }

                    CompassWarning.LOCATION_DISABLED ->
                        FilledTonalButton(onClick = { openLocationSettings() }) {
                            Text(stringResource(Res.string.open_settings))
                        }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun warningText(warning: CompassWarning): String = when (warning) {
    CompassWarning.NO_MAGNETOMETER -> stringResource(Res.string.compass_no_magnetometer)
    CompassWarning.NO_LOCATION_PERMISSION -> stringResource(Res.string.compass_no_location_permission)
    CompassWarning.LOCATION_DISABLED -> stringResource(Res.string.compass_location_disabled)
    CompassWarning.NO_LOCATION_FIX -> stringResource(Res.string.compass_no_location_fix)
}

@Composable
private fun GuidanceBanner(state: YagiPointingUiState) {
    val turn = state.turnBy ?: return
    val lines = buildList {
        if (!state.isAzimuthAligned) {
            val amount = deg(abs(turn), 0)
            add(stringResource(if (turn > 0) Res.string.yagi_turn_right else Res.string.yagi_turn_left, amount))
        }
        val tilt = state.tiltBy
        if (tilt != null && !state.isElevationAligned) {
            val amount = deg(abs(tilt), 1)
            add(stringResource(if (tilt > 0) Res.string.yagi_tilt_up else Res.string.yagi_tilt_down, amount))
        }
    }
    val color =
        when {
            state.isOnTarget -> OnTargetColor
            abs(turn) <= NEAR_AZIMUTH_DEG -> NearColor
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val onColor = if (state.isOnTarget) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Text(
            text = if (state.isOnTarget) stringResource(Res.string.yagi_on_target) else lines.joinToString("  ·  "),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = onColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 12.dp),
        )
    }
}

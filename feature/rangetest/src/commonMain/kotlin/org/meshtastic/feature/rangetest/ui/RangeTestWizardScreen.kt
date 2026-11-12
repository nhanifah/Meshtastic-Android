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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_back_step
import org.meshtastic.core.resources.range_test_error_failed
import org.meshtastic.core.resources.range_test_error_invalid_code
import org.meshtastic.core.resources.range_test_error_no_channel
import org.meshtastic.core.resources.range_test_error_no_destination
import org.meshtastic.core.resources.range_test_error_no_sender
import org.meshtastic.core.resources.range_test_error_no_slot
import org.meshtastic.core.resources.range_test_error_not_connected
import org.meshtastic.core.resources.range_test_next
import org.meshtastic.core.resources.range_test_start_test
import org.meshtastic.core.resources.range_test_step_beacon
import org.meshtastic.core.resources.range_test_step_channel
import org.meshtastic.core.resources.range_test_step_location
import org.meshtastic.core.resources.range_test_step_of
import org.meshtastic.core.resources.range_test_step_pick_sender
import org.meshtastic.core.resources.range_test_step_role
import org.meshtastic.core.resources.range_test_step_share
import org.meshtastic.core.resources.range_test_step_summary
import org.meshtastic.core.resources.range_test_step_target
import org.meshtastic.core.resources.range_test_wizard_title
import org.meshtastic.feature.rangetest.RangeTestRole
import org.meshtastic.feature.rangetest.RangeTestWizardError
import org.meshtastic.feature.rangetest.RangeTestWizardState
import org.meshtastic.feature.rangetest.RangeTestWizardStep
import org.meshtastic.feature.rangetest.RangeTestWizardViewModel

private val WIZARD_MAX_WIDTH = 640.dp

/**
 * The step-by-step setup. One question per screen, with Back / Next at the bottom; the system back gesture steps back
 * through the wizard before leaving it.
 */
@Composable
fun RangeTestWizardScreen(
    viewModel: RangeTestWizardViewModel,
    onNavigateUp: () -> Unit,
    onStart: (RangeTestRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val detected by viewModel.detectedSenders.collectAsStateWithLifecycle()
    val radioHasGps by viewModel.radioHasGps.collectAsStateWithLifecycle()
    val shareCode by viewModel.shareCode.collectAsStateWithLifecycle()
    val data = WizardData(channels, nodes, detected, radioHasGps, shareCode)
    val actions =
        remember(viewModel) {
            WizardActions(
                selectRole = viewModel::selectRole,
                setCreateChannel = viewModel::setCreateChannel,
                selectChannel = viewModel::selectChannel,
                joinFromCode = viewModel::joinFromCode,
                setBroadcast = viewModel::setBroadcast,
                selectDestination = viewModel::selectDestination,
                setInterval = viewModel::setInterval,
                setDuration = viewModel::setDuration,
                setMode = viewModel::setMode,
                selectSender = viewModel::selectSender,
                setNodeQuery = viewModel::setNodeQuery,
            )
        }

    LaunchedEffect(viewModel) { viewModel.started.collect(onStart) }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = !state.isFirstStep,
        onBackCompleted = viewModel::back,
    )

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(stringResource(Res.string.range_test_wizard_title), onNavigateUp) },
        bottomBar = {
            WizardButtons(
                state = state,
                onBack = viewModel::back,
                onNext = viewModel::next,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") { StepHeader(state, Modifier.widthIn(max = WIZARD_MAX_WIDTH)) }
            wizardStep(state, data, actions, Modifier.widthIn(max = WIZARD_MAX_WIDTH).fillMaxWidth())
        }
    }
}

@Composable
private fun StepHeader(state: RangeTestWizardState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.role != null) {
            Text(
                stringResource(Res.string.range_test_step_of, state.stepNumber, state.steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { state.stepNumber.toFloat() / state.steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(stringResource(state.step.title()), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun WizardButtons(state: RangeTestWizardState, onBack: () -> Unit, onNext: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = WIZARD_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    Text(
                        stringResource(error.message()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.isFirstStep) {
                        OutlinedButton(
                            onClick = onBack,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(Res.string.range_test_back_step))
                        }
                    }
                    Button(
                        onClick = onNext,
                        enabled = !state.busy && (state.step != RangeTestWizardStep.ROLE || state.role != null),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(
                                    if (state.step == RangeTestWizardStep.SUMMARY) {
                                        Res.string.range_test_start_test
                                    } else {
                                        Res.string.range_test_next
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun RangeTestWizardStep.title(): StringResource = when (this) {
    RangeTestWizardStep.ROLE -> Res.string.range_test_step_role
    RangeTestWizardStep.CHANNEL -> Res.string.range_test_step_channel
    RangeTestWizardStep.SHARE -> Res.string.range_test_step_share
    RangeTestWizardStep.TARGET -> Res.string.range_test_step_target
    RangeTestWizardStep.BEACON -> Res.string.range_test_step_beacon
    RangeTestWizardStep.PICK_SENDER -> Res.string.range_test_step_pick_sender
    RangeTestWizardStep.LOCATION -> Res.string.range_test_step_location
    RangeTestWizardStep.SUMMARY -> Res.string.range_test_step_summary
}

private fun RangeTestWizardError.message(): StringResource = when (this) {
    RangeTestWizardError.NOT_CONNECTED -> Res.string.range_test_error_not_connected
    RangeTestWizardError.NO_FREE_SLOT -> Res.string.range_test_error_no_slot
    RangeTestWizardError.INVALID_CODE -> Res.string.range_test_error_invalid_code
    RangeTestWizardError.NO_CHANNEL -> Res.string.range_test_error_no_channel
    RangeTestWizardError.NO_SENDER -> Res.string.range_test_error_no_sender
    RangeTestWizardError.NO_DESTINATION -> Res.string.range_test_error_no_destination
    RangeTestWizardError.FAILED -> Res.string.range_test_error_failed
}

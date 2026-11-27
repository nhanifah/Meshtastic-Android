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
package org.meshtastic.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_cancel_note
import org.meshtastic.core.resources.voice_mic_permission
import org.meshtastic.core.resources.voice_push_to_talk
import org.meshtastic.core.resources.voice_record
import org.meshtastic.core.resources.voice_recording
import org.meshtastic.core.resources.voice_result_forwarded
import org.meshtastic.core.resources.voice_result_no_radio
import org.meshtastic.core.resources.voice_result_queued
import org.meshtastic.core.resources.voice_result_rejected
import org.meshtastic.core.resources.voice_result_too_slow
import org.meshtastic.core.resources.voice_result_unsupported
import org.meshtastic.core.resources.voice_send_note
import org.meshtastic.core.resources.voice_someone_talking
import org.meshtastic.core.resources.voice_talking_live
import org.meshtastic.core.resources.voice_unavailable_lora_busy
import org.meshtastic.core.resources.voice_unavailable_lora_slow
import org.meshtastic.core.resources.voice_unavailable_no_link
import org.meshtastic.core.resources.voice_via_lora
import org.meshtastic.core.resources.voice_via_mqtt
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Mic
import org.meshtastic.core.ui.icon.VolumeUp
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberMicrophonePermissionState
import org.meshtastic.feature.voice.ComposerVoiceState
import org.meshtastic.feature.voice.VoiceComposerViewModel
import org.meshtastic.feature.voice.VoiceNotice

private const val NOTICE_MS = 4_000L
private const val SECONDS_PER_MINUTE = 60
private const val MILLIS = 1_000

internal fun formatClock(ms: Long): String {
    val total = (ms / MILLIS).toInt()
    return "${total / SECONDS_PER_MINUTE}:${(total % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/**
 * Beside the message field: tap the mic to record a voice note (then send or discard it), or hold the talk button for
 * live push-to-talk. Both ask for the microphone first.
 */
@Composable
internal fun VoiceComposerActions(contactKey: String, enabled: Boolean, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    if (!viewModel.supported) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicrophonePermissionState()
    val micGranted = mic.status == PermissionStatus.GRANTED
    val currentKey by rememberUpdatedState(contactKey)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (val s = state) {
            is ComposerVoiceState.RecordingNote -> {
                IconButton(onClick = { viewModel.finishNote(currentKey, send = false) }) {
                    Icon(MeshtasticIcons.Close, contentDescription = stringResource(Res.string.voice_cancel_note))
                }
                Text(
                    text = stringResource(Res.string.voice_recording, formatClock(s.elapsedMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                IconButton(onClick = { viewModel.finishNote(currentKey, send = true) }) {
                    Icon(
                        MeshtasticIcons.Check,
                        contentDescription = stringResource(Res.string.voice_send_note),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            else -> {
                IconButton(
                    enabled = enabled && state == ComposerVoiceState.Idle,
                    onClick = { if (micGranted) viewModel.startNote(currentKey) else mic.request() },
                ) {
                    Icon(MeshtasticIcons.Mic, contentDescription = stringResource(Res.string.voice_record))
                }
                PushToTalkButton(
                    talking = state is ComposerVoiceState.Talking,
                    enabled = enabled,
                    onPress = { if (micGranted) viewModel.startTalk(currentKey) else mic.request() },
                    onRelease = viewModel::stopTalk,
                )
            }
        }
    }
}

@Composable
private fun PushToTalkButton(talking: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val label = stringResource(Res.string.voice_push_to_talk)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier =
        Modifier.padding(4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (talking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
            )
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press()
                        tryAwaitRelease()
                        release()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MeshtasticIcons.VolumeUp,
            contentDescription = null,
            tint = if (talking) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Above the composer: who is talking live in this conversation, our own live state, or the last voice notice. */
@Composable
internal fun VoiceLiveBanner(contactKey: String, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    if (!viewModel.supported) return
    val talkers by viewModel.talkers.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val mic = rememberMicrophonePermissionState()

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            viewModel.clearNotice()
        }
    }

    val talker = talkers[contactKey]
    val talking = state as? ComposerVoiceState.Talking
    val text =
        when {
            talking != null ->
                stringResource(Res.string.voice_talking_live) +
                    " · " +
                    formatClock(talking.elapsedMs) +
                    " · " +
                    stringResource(if (talking.viaMqtt) Res.string.voice_via_mqtt else Res.string.voice_via_lora)

            talker != null ->
                stringResource(Res.string.voice_someone_talking, talker.name) +
                    " · " +
                    stringResource(if (talker.viaMqtt) Res.string.voice_via_mqtt else Res.string.voice_via_lora)

            mic.status == PermissionStatus.PERMANENTLY_DENIED -> stringResource(Res.string.voice_mic_permission)

            notice != null -> stringResource(notice!!.message)

            else -> return
        }
    Surface(
        modifier = modifier,
        color =
        if (talking != null || talker != null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal val VoiceNotice.message: StringResource
    get() =
        when (this) {
            VoiceNotice.QUEUED -> Res.string.voice_result_queued
            VoiceNotice.REJECTED -> Res.string.voice_result_rejected
            VoiceNotice.TOO_SLOW_FOR_LORA -> Res.string.voice_result_too_slow
            VoiceNotice.NO_RADIO -> Res.string.voice_result_no_radio
            VoiceNotice.UNSUPPORTED -> Res.string.voice_result_unsupported
            VoiceNotice.FORWARDED -> Res.string.voice_result_forwarded
            VoiceNotice.MIC_FAILED -> Res.string.voice_mic_permission
            VoiceNotice.LIVE_NO_LINK -> Res.string.voice_unavailable_no_link
            VoiceNotice.LIVE_LORA_TOO_SLOW -> Res.string.voice_unavailable_lora_slow
            VoiceNotice.LIVE_LORA_BUSY -> Res.string.voice_unavailable_lora_busy
        }

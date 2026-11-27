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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.voice.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.voice_ptt_tap_to_stop
import org.meshtastic.core.resources.voice_ptt_tap_to_talk
import org.meshtastic.core.resources.voice_ptt_title
import org.meshtastic.core.resources.voice_someone_talking
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Mic
import org.meshtastic.core.ui.icon.VolumeUp
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberMicrophonePermissionState
import org.meshtastic.feature.voice.ComposerVoiceState
import org.meshtastic.feature.voice.VoiceComposerViewModel

private val MAX_CIRCLE = 280.dp
private const val CIRCLE_WIDTH_FRACTION = 0.7f
private const val HOLD_TO_TALK_MS = 400L
private const val PULSE_MS = 900
private const val PULSE_SCALE = 1.12f

/** The conversation's top-bar push-to-talk button; it opens [PushToTalkScreen]. Tinted while someone is talking. */
@Composable
internal fun VoicePushToTalkAction(contactKey: String, title: String, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    if (!viewModel.supported) return
    var open by rememberSaveable { mutableStateOf(false) }
    val talkers by viewModel.talkers.collectAsStateWithLifecycle()
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            MeshtasticIcons.VolumeUp,
            contentDescription = stringResource(Res.string.voice_ptt_title),
            tint = if (talkers[contactKey] != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
    if (open) PushToTalkScreen(contactKey = contactKey, title = title, onClose = { open = false })
}

/**
 * Full-screen walkie-talkie for one conversation: a large round button. Tap it to start talking and tap again to stop,
 * or hold it and let go, like a radio's PTT key. Who else is talking, and why live audio is unavailable, show below.
 */
@Composable
private fun PushToTalkScreen(contactKey: String, title: String, onClose: () -> Unit) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    DisposableEffect(Unit) { onDispose { viewModel.stopTalk() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(MeshtasticIcons.Close, contentDescription = stringResource(Res.string.close))
                    }
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(Res.string.voice_ptt_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TalkCircle(contactKey = contactKey)
                Spacer(Modifier.weight(1f))
                VoiceLiveBanner(contactKey = contactKey, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun TalkCircle(contactKey: String) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val talkers by viewModel.talkers.collectAsStateWithLifecycle()
    val mic = rememberMicrophonePermissionState()
    val micGranted by rememberUpdatedState(mic.status == PermissionStatus.GRANTED)
    val requestMic by rememberUpdatedState { mic.request() }
    val currentKey by rememberUpdatedState(contactKey)
    val talking = state as? ComposerVoiceState.Talking
    val incoming = talkers[contactKey]
    val pulse by
        rememberInfiniteTransition()
            .animateFloat(
                initialValue = 1f,
                targetValue = PULSE_SCALE,
                animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            )
    val label =
        stringResource(if (talking != null) Res.string.voice_ptt_tap_to_stop else Res.string.voice_ptt_tap_to_talk)
    val color =
        when {
            talking != null -> MaterialTheme.colorScheme.error
            incoming != null -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.primary
        }
    val onColor =
        when {
            talking != null -> MaterialTheme.colorScheme.onError
            incoming != null -> MaterialTheme.colorScheme.onSecondary
            else -> MaterialTheme.colorScheme.onPrimary
        }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth * CIRCLE_WIDTH_FRACTION, MAX_CIRCLE)
        // A soft halo that breathes while we are on air.
        Box(
            Modifier.size(size)
                .graphicsLayer {
                    val s = if (talking != null) pulse else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(color.copy(alpha = 0.25f)),
        )
        Box(
            modifier =
            Modifier.size(size * 0.86f)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (viewModel.state.value is ComposerVoiceState.Talking) {
                            viewModel.stopTalk()
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        if (!micGranted) {
                            requestMic()
                            return@awaitEachGesture
                        }
                        viewModel.startTalk(currentKey)
                        val up = waitForUpOrCancellation()
                        // Held like a radio's PTT key: letting go ends the burst. A quick tap leaves it on.
                        if (up == null || up.uptimeMillis - down.uptimeMillis >= HOLD_TO_TALK_MS) {
                            viewModel.stopTalk()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    MeshtasticIcons.Mic,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(size * 0.28f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                    when {
                        talking != null -> formatClock(talking.elapsedMs)
                        incoming != null -> stringResource(Res.string.voice_someone_talking, incoming.name)
                        else -> label
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = onColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (talking != null) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = onColor)
                }
            }
        }
    }
}

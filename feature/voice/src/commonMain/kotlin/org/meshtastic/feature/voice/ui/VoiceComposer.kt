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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_cancel_note
import org.meshtastic.core.resources.voice_hold_to_record
import org.meshtastic.core.resources.voice_mic_permission
import org.meshtastic.core.resources.voice_push_to_talk
import org.meshtastic.core.resources.voice_record
import org.meshtastic.core.resources.voice_result_forwarded
import org.meshtastic.core.resources.voice_result_no_radio
import org.meshtastic.core.resources.voice_result_queued
import org.meshtastic.core.resources.voice_result_rejected
import org.meshtastic.core.resources.voice_result_too_slow
import org.meshtastic.core.resources.voice_result_unsupported
import org.meshtastic.core.resources.voice_send_note
import org.meshtastic.core.resources.voice_slide_to_cancel
import org.meshtastic.core.resources.voice_slide_up_to_lock
import org.meshtastic.core.resources.voice_someone_talking
import org.meshtastic.core.resources.voice_talking_live
import org.meshtastic.core.resources.voice_unavailable_lora_busy
import org.meshtastic.core.resources.voice_unavailable_lora_slow
import org.meshtastic.core.resources.voice_unavailable_no_link
import org.meshtastic.core.resources.voice_via_lora
import org.meshtastic.core.resources.voice_via_mqtt
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.KeyboardArrowLeft
import org.meshtastic.core.ui.icon.KeyboardArrowUp
import org.meshtastic.core.ui.icon.Lock
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Mic
import org.meshtastic.core.ui.icon.Send
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

/** The small hold-to-talk button at the start of the message field: live push-to-talk, released to stop. */
@Composable
internal fun VoicePushToTalkButton(contactKey: String, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    if (!viewModel.supported) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicrophonePermissionState()
    val micGranted = mic.status == PermissionStatus.GRANTED
    val currentKey by rememberUpdatedState(contactKey)
    val talking = state is ComposerVoiceState.Talking
    val enabled = state == ComposerVoiceState.Idle || talking
    val label = stringResource(Res.string.voice_push_to_talk)
    val press by rememberUpdatedState { if (micGranted) viewModel.startTalk(currentKey) else mic.request() }

    Box(
        modifier =
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (talking) MaterialTheme.colorScheme.error else Color.Transparent)
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press()
                        tryAwaitRelease()
                        viewModel.stopTalk()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MeshtasticIcons.VolumeUp,
            contentDescription = null,
            tint =
            when {
                talking -> MaterialTheme.colorScheme.onError
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}

/** Follows one held finger until it lifts, slides far enough left (cancel) or up (lock), or the note ends. */
@Suppress("ReturnCount")
private suspend fun AwaitPointerEventScope.trackHold(
    down: PointerInputChange,
    cancelPx: Float,
    lockPx: Float,
    stillRecording: () -> Boolean,
    onSlide: (Float) -> Unit,
): HoldOutcome {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return HoldOutcome.SEND
        if (!change.pressed) {
            return if (change.uptimeMillis - down.uptimeMillis < TAP_MS) HoldOutcome.TAP else HoldOutcome.SEND
        }
        change.consume()
        // Reached the length limit, so it was already sent.
        if (!stillRecording()) return HoldOutcome.ALREADY_ENDED
        val dx = change.position.x - down.position.x
        val dy = change.position.y - down.position.y
        if (dx < -cancelPx) return HoldOutcome.CANCEL
        if (dy < -lockPx) return HoldOutcome.LOCK
        onSlide(-dx / cancelPx)
    }
}

private enum class HoldOutcome {
    SEND,
    TAP,
    CANCEL,
    LOCK,
    ALREADY_ENDED,
}

/**
 * The round button at the end of the composer while the field is empty, used like WhatsApp's: hold to record a voice
 * note, release to send, slide left to cancel, slide up to lock. A plain tap only explains that it must be held. Once
 * locked it turns into a send button.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun VoiceRecordButton(contactKey: String, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    if (!viewModel.supported) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicrophonePermissionState()
    val micGranted by rememberUpdatedState(mic.status == PermissionStatus.GRANTED)
    val requestMic by rememberUpdatedState { mic.request() }
    val currentKey by rememberUpdatedState(contactKey)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelPx = with(density) { CANCEL_DISTANCE.toPx() }
    val lockPx = with(density) { LOCK_DISTANCE.toPx() }
    val recording = state as? ComposerVoiceState.RecordingNote
    val idleOrRecording = state == ComposerVoiceState.Idle || recording != null
    val scale by animateFloatAsState(if (recording != null && !recording.locked) HOLD_SCALE else 1f)

    if (recording?.locked == true) {
        RoundButton(
            icon = MeshtasticIcons.Send,
            label = stringResource(Res.string.voice_send_note),
            onClick = { viewModel.finishNote(currentKey, send = true) },
            modifier = modifier,
        )
        return
    }

    val label = stringResource(Res.string.voice_record)
    Box(
        modifier =
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (idleOrRecording) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
            )
            .semantics { contentDescription = label }
            .pointerInput(viewModel) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!micGranted) {
                        requestMic()
                        return@awaitEachGesture
                    }
                    if (viewModel.state.value != ComposerVoiceState.Idle) return@awaitEachGesture
                    down.consume()
                    viewModel.startNote(currentKey)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val outcome =
                        try {
                            trackHold(
                                down = down,
                                cancelPx = cancelPx,
                                lockPx = lockPx,
                                stillRecording = { viewModel.state.value is ComposerVoiceState.RecordingNote },
                                onSlide = viewModel::setSlide,
                            )
                        } catch (e: CancellationException) {
                            viewModel.finishNote(currentKey, send = false)
                            throw e
                        }
                    when (outcome) {
                        HoldOutcome.SEND -> viewModel.finishNote(currentKey, send = true)

                        HoldOutcome.TAP -> {
                            viewModel.finishNote(currentKey, send = false)
                            viewModel.showNotice(VoiceNotice.HOLD_TO_RECORD)
                        }

                        HoldOutcome.CANCEL -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.finishNote(currentKey, send = false)
                        }

                        HoldOutcome.LOCK -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.lockNote()
                        }

                        HoldOutcome.ALREADY_ENDED -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MeshtasticIcons.Mic,
            contentDescription = null,
            tint =
            if (idleOrRecording) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier =
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * Covers the message field while a note is recorded: a blinking red dot and the time, then either "‹ Slide to cancel"
 * (following the finger) with a lock hint, or, once locked, a discard button.
 */
@Suppress("LongMethod")
@Composable
internal fun VoiceRecordingBar(contactKey: String, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recording = state as? ComposerVoiceState.RecordingNote ?: return
    val slide by viewModel.slideToCancel.collectAsStateWithLifecycle()
    val blink by
        rememberInfiniteTransition()
            .animateFloat(
                initialValue = 1f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(tween(BLINK_MS), RepeatMode.Reverse),
            )

    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp)
                    .graphicsLayer { alpha = blink }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatClock(recording.elapsedMs) + " / " + formatClock(recording.maxMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (recording.locked) {
                IconButton(onClick = { viewModel.finishNote(contactKey, send = false) }) {
                    Icon(
                        MeshtasticIcons.Delete,
                        contentDescription = stringResource(Res.string.voice_cancel_note),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Row(
                    modifier =
                    Modifier.graphicsLayer {
                        translationX = -slide * SLIDE_FOLLOW.toPx()
                        alpha = 1f - slide * SLIDE_FADE
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        MeshtasticIcons.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(Res.string.voice_slide_to_cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Icon(
                        MeshtasticIcons.KeyboardArrowUp,
                        contentDescription = stringResource(Res.string.voice_slide_up_to_lock),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Icon(
                        MeshtasticIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private val CANCEL_DISTANCE = 120.dp
private val LOCK_DISTANCE = 80.dp
private val SLIDE_FOLLOW = 40.dp
private const val SLIDE_FADE = 0.7f
private const val HOLD_SCALE = 1.2f
private const val TAP_MS = 300L
private const val BLINK_MS = 600

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
            VoiceNotice.HOLD_TO_RECORD -> Res.string.voice_hold_to_record
        }

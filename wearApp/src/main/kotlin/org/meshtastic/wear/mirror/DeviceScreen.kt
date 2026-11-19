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
package org.meshtastic.wear.mirror

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.repository.MirrorFrame
import org.meshtastic.core.repository.MirrorPalette
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.message_device_managed
import org.meshtastic.core.resources.mirror_back
import org.meshtastic.core.resources.mirror_device_screen
import org.meshtastic.core.resources.mirror_key_left
import org.meshtastic.core.resources.mirror_key_ok
import org.meshtastic.core.resources.mirror_key_right
import org.meshtastic.core.resources.mirror_not_connected
import org.meshtastic.core.resources.mirror_unsupported
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.KeyboardArrowLeft
import org.meshtastic.core.ui.icon.KeyboardArrowRight
import org.meshtastic.core.ui.icon.MeshtasticIcons

/**
 * The radio's screen, live. The bezel (or crown) steps Up/Down like the rotary encoder on a radio; the buttons send
 * Left, OK (hold for a long press) and Right; Back sits on top. Tapping the image presses OK, or touches the panel at
 * that spot on radios with a touch screen.
 */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier) {
    val viewModel: DeviceScreenViewModel = koinViewModel()
    val status by viewModel.state.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Mirror only while the screen is actually on: a dimmed or backgrounded watch stops the stream.
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible(true)
        onStopOrDispose { viewModel.onVisible(false) }
    }
    // Remote control is useless if the watch sleeps between key presses.
    val view = LocalView.current
    DisposableEffect(status) {
        view.keepScreenOn = status == DeviceScreenStatus.READY
        onDispose { view.keepScreenOn = false }
    }

    val press: (Int) -> Unit = { event ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.sendKey(event)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rotary = remember { mutableFloatStateOf(0f) }

    ScreenScaffold {
        Column(
            modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .bezelSteps(rotary, press)
                .focusRequester(focus)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KeyButton(MeshtasticIcons.ArrowBack, Res.string.mirror_back, small = true) { press(DeviceInput.BACK) }
            Spacer(Modifier.height(GAP))
            val current = frame
            if (status == DeviceScreenStatus.READY && current != null) {
                MirrorImage(
                    frame = current,
                    palette = palette,
                    onTap = { x, y ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.tap(x, y)
                    },
                    onLongPress = { x, y ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.longPress(x, y)
                    },
                )
            } else {
                StatusBox(status)
            }
            Spacer(Modifier.height(GAP))
            KeyRow(
                press = press,
                onLongOk = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.sendKey(DeviceInput.SELECT_LONG)
                },
            )
        }
    }
}

/** Bezel or crown turns become Up/Down steps, like the rotary encoder on a radio. */
private fun Modifier.bezelSteps(accumulated: MutableFloatState, press: (Int) -> Unit): Modifier =
    onRotaryScrollEvent { event ->
        accumulated.floatValue += event.verticalScrollPixels
        if (kotlin.math.abs(accumulated.floatValue) >= ROTARY_STEP_PX) {
            press(if (accumulated.floatValue > 0) DeviceInput.DOWN else DeviceInput.UP)
            accumulated.floatValue = 0f
        }
        true
    }

@Composable
private fun KeyRow(press: (Int) -> Unit, onLongOk: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
        KeyButton(MeshtasticIcons.KeyboardArrowLeft, Res.string.mirror_key_left) { press(DeviceInput.LEFT) }
        FilledTonalIconButton(
            onClick = { press(DeviceInput.SELECT) },
            onLongClick = onLongOk,
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
        ) {
            Text(stringResource(Res.string.mirror_key_ok), style = MaterialTheme.typography.labelMedium)
        }
        KeyButton(MeshtasticIcons.KeyboardArrowRight, Res.string.mirror_key_right) { press(DeviceInput.RIGHT) }
    }
}

@Composable
private fun KeyButton(icon: ImageVector, label: StringResource, small: Boolean = false, onClick: () -> Unit) {
    val size = if (small) IconButtonDefaults.ExtraSmallButtonSize else IconButtonDefaults.SmallButtonSize
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The frame at 1:1, scaled up with nearest-neighbour filtering so OLED pixels stay crisp. */
@Composable
private fun MirrorImage(
    frame: MirrorFrame,
    palette: MirrorPalette?,
    onTap: (Int, Int) -> Unit,
    onLongPress: (Int, Int) -> Unit,
) {
    val bitmap by
        produceState<ImageBitmap?>(null, frame, palette) {
            value =
                withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(mirrorArgb(frame, palette), frame.width, frame.height, Bitmap.Config.ARGB_8888)
                        .asImageBitmap()
                }
        }
    val sizing =
        Modifier.fillMaxWidth(IMAGE_WIDTH_FRACTION)
            .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .pointerInput(frame.width, frame.height) {
                fun deviceX(px: Float) = (px / size.width * frame.width).toInt().coerceIn(0, frame.width - 1)
                fun deviceY(px: Float) = (px / size.height * frame.height).toInt().coerceIn(0, frame.height - 1)
                detectTapGestures(
                    onTap = { onTap(deviceX(it.x), deviceY(it.y)) },
                    onLongPress = { onLongPress(deviceX(it.x), deviceY(it.y)) },
                )
            }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(Res.string.mirror_device_screen),
            modifier = sizing,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
        )
    } ?: Box(sizing)
}

@Composable
private fun StatusBox(status: DeviceScreenStatus) {
    val text =
        when (status) {
            DeviceScreenStatus.NOT_CONNECTED -> Res.string.mirror_not_connected

            DeviceScreenStatus.UNSUPPORTED -> Res.string.mirror_unsupported

            DeviceScreenStatus.MANAGED -> Res.string.message_device_managed

            // Armed, waiting for the first frame.
            DeviceScreenStatus.READY -> Res.string.mirror_device_screen
        }
    Box(
        modifier =
        Modifier.fillMaxWidth(IMAGE_WIDTH_FRACTION)
            .aspectRatio(DEFAULT_ASPECT)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// A 2:1 OLED at this width leaves the Back button and the key row clear of the round display's edge.
private const val IMAGE_WIDTH_FRACTION = 0.72f
private const val DEFAULT_ASPECT = 2f
private val GAP = 6.dp

// One bezel detent on the Galaxy Watch is well above this; a crown needs a short turn per step.
private const val ROTARY_STEP_PX = 24f

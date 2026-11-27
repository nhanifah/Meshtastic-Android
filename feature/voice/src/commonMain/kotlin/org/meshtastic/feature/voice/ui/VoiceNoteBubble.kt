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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okio.ByteString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_forward_lora
import org.meshtastic.core.resources.voice_missing_parts
import org.meshtastic.core.resources.voice_note
import org.meshtastic.core.resources.voice_pause
import org.meshtastic.core.resources.voice_play
import org.meshtastic.core.ui.icon.CellTower
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Pause
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.feature.voice.VoiceClip
import org.meshtastic.feature.voice.VoiceComposerViewModel

private val PlayButtonSize = 36.dp
private val TrackWidth = 132.dp

/**
 * The inside of a voice-note bubble, kept as compact as a short text message: a round play button, a progress track
 * with the time and bit rate under it, and (when a radio is connected) a small button that pushes the note onto LoRa.
 */
@Composable
internal fun VoiceNoteBody(
    payload: ByteString,
    @Suppress("UnusedParameter") fromLocal: Boolean,
    contactKey: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<VoiceComposerViewModel>()
    val clip = remember(payload) { VoiceClip.fromStored(payload) }
    if (clip == null) {
        Text(stringResource(Res.string.voice_note), color = contentColor, modifier = modifier)
        return
    }
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val radioReady by viewModel.radioReady.collectAsStateWithLifecycle()
    val playing = playback.note == payload
    val progress = if (playing && playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(playing = playing, enabled = viewModel.supported, tint = contentColor) {
                viewModel.togglePlay(payload)
            }
            Spacer(Modifier.width(10.dp))
            ProgressAndTime(
                progress = progress,
                time =
                if (playing) {
                    formatClock(playback.positionMs.toLong()) + " / " + formatClock(clip.durationMs.toLong())
                } else {
                    formatClock(clip.durationMs.toLong())
                },
                bitRate = clip.codec.bitsPerSecond,
                color = contentColor,
            )
            // Anyone can push a note onto the local mesh: someone else's that came over MQTT, or our own sent online.
            if (radioReady && viewModel.supported) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { viewModel.forwardToLora(contactKey, payload) },
                    modifier = Modifier.size(PlayButtonSize),
                ) {
                    Icon(
                        MeshtasticIcons.CellTower,
                        contentDescription = stringResource(Res.string.voice_forward_lora),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (clip.missingChunks > 0) {
            Text(
                text = stringResource(Res.string.voice_missing_parts, clip.missingChunks, clip.chunks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier.size(PlayButtonSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) MeshtasticIcons.Pause else MeshtasticIcons.PlayArrow,
            contentDescription = stringResource(if (playing) Res.string.voice_pause else Res.string.voice_play),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ProgressAndTime(progress: Float, time: String, bitRate: Int, color: Color) {
    Column(modifier = Modifier.width(TrackWidth)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.width(TrackWidth).height(4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.width(TrackWidth), verticalAlignment = Alignment.CenterVertically) {
            Text(text = time, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.weight(1f))
            Text(
                text = "$bitRate bps",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.7f),
            )
        }
    }
}

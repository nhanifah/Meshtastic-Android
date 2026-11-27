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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Pause
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.feature.voice.VoiceClip
import org.meshtastic.feature.voice.VoiceComposerViewModel

/** The inside of a voice-note bubble: play/pause, length (or position while playing), losses, and forward to LoRa. */
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

    Column(modifier = modifier.widthIn(min = 200.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = viewModel.supported, onClick = { viewModel.togglePlay(payload) }) {
                Icon(
                    if (playing) MeshtasticIcons.Pause else MeshtasticIcons.PlayArrow,
                    contentDescription = stringResource(if (playing) Res.string.voice_pause else Res.string.voice_play),
                    tint = contentColor,
                )
            }
            Column {
                if (playing && playback.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { playback.positionMs.toFloat() / playback.durationMs },
                        modifier = Modifier.width(120.dp),
                    )
                }
                Text(
                    text =
                    if (playing) {
                        formatClock(playback.positionMs.toLong()) + " / " + formatClock(clip.durationMs.toLong())
                    } else {
                        stringResource(Res.string.voice_note) + " · " + formatClock(clip.durationMs.toLong())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${clip.codec.bitsPerSecond} bps",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
        if (clip.missingChunks > 0) {
            Text(
                text = stringResource(Res.string.voice_missing_parts, clip.missingChunks, clip.chunks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Anyone can push a note onto the local mesh: someone else's that came over MQTT, or our own sent online.
        if (radioReady && viewModel.supported) {
            TextButton(onClick = { viewModel.forwardToLora(contactKey, payload) }) {
                Text(stringResource(Res.string.voice_forward_lora), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

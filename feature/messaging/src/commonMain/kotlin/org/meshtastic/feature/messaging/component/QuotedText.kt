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
package org.meshtastic.feature.messaging.component

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.Message
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.voice_note
import org.meshtastic.proto.PortNum

/** What a reply quotes for [this]: a voice note has no text of its own, so it is named instead of left blank. */
@Composable
internal fun Message.quotedText(): String =
    if (portNum == PortNum.AUDIO_APP.value) "🎤 ${stringResource(Res.string.voice_note)}" else text

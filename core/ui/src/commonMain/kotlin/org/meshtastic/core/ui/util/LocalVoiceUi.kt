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
package org.meshtastic.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import okio.ByteString

/** The port a voice note is stored under in the message database (`PortNum.AUDIO_APP`). */
const val VOICE_NOTE_PORT_NUM: Int = 9

/**
 * The voice feature's pieces of the conversation screen, provided by the app from `:feature:voice` so that
 * `:feature:messaging` does not depend on it. Null (the default) hides voice entirely.
 */
interface VoiceUi {
    /** The body of a voice-note bubble: play button, length, and what was lost on the way. */
    @Composable
    fun NoteBody(payload: ByteString, fromLocal: Boolean, contactKey: String, contentColor: Color, modifier: Modifier)

    /** The top-bar button that opens the push-to-talk screen (one large round talk button) for [contactKey]. */
    @Composable fun PushToTalkAction(contactKey: String, title: String, modifier: Modifier)

    /**
     * The round button at the end of the composer while the field is empty: hold to record a voice note, release to
     * send, slide left to cancel, slide up to lock hands-free.
     */
    @Composable fun RecordButton(contactKey: String, modifier: Modifier)

    /** Drawn over the message field while a note is being recorded (timer, slide-to-cancel); nothing otherwise. */
    @Composable fun RecordingBar(contactKey: String, modifier: Modifier)

    /** "X is talking" while a live push-to-talk burst for [contactKey] is playing; draws nothing otherwise. */
    @Composable fun LiveBanner(contactKey: String, modifier: Modifier)
}

@Suppress("CompositionLocalAllowlist")
val LocalVoiceUi = staticCompositionLocalOf<VoiceUi?> { null }

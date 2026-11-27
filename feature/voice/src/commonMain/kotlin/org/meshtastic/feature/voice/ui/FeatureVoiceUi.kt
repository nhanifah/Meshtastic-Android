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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import okio.ByteString
import org.koin.compose.koinInject
import org.meshtastic.core.ui.util.VoiceUi
import org.meshtastic.feature.voice.VoiceService

/** The voice pieces of the conversation screen; the app provides this as `LocalVoiceUi`. */
object FeatureVoiceUi : VoiceUi {
    @Composable
    override fun NoteBody(
        payload: ByteString,
        fromLocal: Boolean,
        contactKey: String,
        contentColor: Color,
        modifier: Modifier,
    ) = VoiceNoteBody(payload, fromLocal, contactKey, contentColor, modifier)

    @Composable
    override fun PushToTalkAction(contactKey: String, title: String, modifier: Modifier) =
        VoicePushToTalkAction(contactKey, title, modifier)

    @Composable
    override fun RecordButton(contactKey: String, modifier: Modifier) = VoiceRecordButton(contactKey, modifier)

    @Composable
    override fun RecordingBar(contactKey: String, modifier: Modifier) = VoiceRecordingBar(contactKey, modifier)

    @Composable override fun LiveBanner(contactKey: String, modifier: Modifier) = VoiceLiveBanner(contactKey, modifier)
}

/**
 * Starts the voice engine once the app's UI is up, so voice notes and push-to-talk are heard on every screen and keep
 * being heard (application scope) after the app goes to the background. Call it once near the root of the app.
 */
@Composable
fun StartVoiceEngine() {
    val service = koinInject<VoiceService>()
    LaunchedEffect(service) { service.start() }
}

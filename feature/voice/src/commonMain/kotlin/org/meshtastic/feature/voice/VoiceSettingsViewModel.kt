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
package org.meshtastic.feature.voice

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.repository.VoicePrefs

/** Settings → Voice. The MQTT server is shown, not edited: it is the radio's MQTT server. */
@KoinViewModel
class VoiceSettingsViewModel(private val prefs: VoicePrefs, private val service: VoiceService) : ViewModel() {
    val supported: Boolean
        get() = service.supported

    val mqttStatus: StateFlow<VoiceMqttStatus>
        get() = service.mqttStatus

    val codecWireId: StateFlow<Int> = prefs.codecWireId
    val maxNoteSeconds: StateFlow<Int> = prefs.maxNoteSeconds
    val noteTransport: StateFlow<String> = prefs.noteTransport
    val loraRelay: StateFlow<Boolean> = prefs.loraRelay
    val gatewayEnabled: StateFlow<Boolean> = prefs.gatewayEnabled
    val autoplayLive: StateFlow<Boolean> = prefs.autoplayLive

    fun setCodec(codec: VoiceCodec) = prefs.setCodecWireId(codec.wireId)

    fun setMaxNoteSeconds(seconds: Int) = prefs.setMaxNoteSeconds(seconds)

    fun setNoteTransport(transport: VoiceNoteTransport) = prefs.setNoteTransport(transport.name)

    fun setLoraRelay(value: Boolean) = prefs.setLoraRelay(value)

    fun setGatewayEnabled(value: Boolean) = prefs.setGatewayEnabled(value)

    fun setAutoplayLive(value: Boolean) = prefs.setAutoplayLive(value)

    companion object {
        val NOTE_LENGTHS_SECONDS = listOf(15, 30, 60, 90, 120)
    }
}

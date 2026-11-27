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
package org.meshtastic.core.prefs.voice

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.VoiceDataStore
import org.meshtastic.core.repository.VoicePrefs

@Single
class VoicePrefsImpl(private val dataStore: VoiceDataStore, dispatchers: CoroutineDispatchers) : VoicePrefs {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private fun <T> pref(key: Preferences.Key<T>, default: T): StateFlow<T> =
        dataStore.data.map { it[key] ?: default }.stateIn(scope, SharingStarted.Eagerly, default)

    private fun <T> write(key: Preferences.Key<T>, value: T) {
        scope.launch { dataStore.edit { it[key] = value } }
    }

    override val codecWireId = pref(CODEC, DEFAULT_CODEC)
    override val maxNoteSeconds = pref(MAX_NOTE_SECONDS, DEFAULT_MAX_NOTE_SECONDS)
    override val noteTransport = pref(NOTE_TRANSPORT, DEFAULT_NOTE_TRANSPORT)
    override val loraRelay = pref(LORA_RELAY, true)
    override val gatewayEnabled = pref(GATEWAY, false)
    override val autoplayLive = pref(AUTOPLAY_LIVE, true)
    override val lastNodeNum = pref(LAST_NODE_NUM, 0)

    override fun setCodecWireId(value: Int) = write(CODEC, value)

    override fun setMaxNoteSeconds(value: Int) = write(MAX_NOTE_SECONDS, value)

    override fun setNoteTransport(value: String) = write(NOTE_TRANSPORT, value)

    override fun setLoraRelay(value: Boolean) = write(LORA_RELAY, value)

    override fun setGatewayEnabled(value: Boolean) = write(GATEWAY, value)

    override fun setAutoplayLive(value: Boolean) = write(AUTOPLAY_LIVE, value)

    override fun setLastNodeNum(value: Int) = write(LAST_NODE_NUM, value)

    companion object {
        /** Codec2 1300 bps, the voice protocol's wire id 3. */
        const val DEFAULT_CODEC = 3
        const val DEFAULT_MAX_NOTE_SECONDS = 30
        const val DEFAULT_NOTE_TRANSPORT = "AUTO"

        private val CODEC = intPreferencesKey("codec_wire_id")
        private val MAX_NOTE_SECONDS = intPreferencesKey("max_note_seconds")
        private val NOTE_TRANSPORT = stringPreferencesKey("note_transport")
        private val LORA_RELAY = booleanPreferencesKey("lora_relay")
        private val GATEWAY = booleanPreferencesKey("gateway_enabled")
        private val AUTOPLAY_LIVE = booleanPreferencesKey("autoplay_live")
        private val LAST_NODE_NUM = intPreferencesKey("last_node_num")
    }
}

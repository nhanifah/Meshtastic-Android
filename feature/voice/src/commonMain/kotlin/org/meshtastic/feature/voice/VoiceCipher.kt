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

import okio.ByteString

/**
 * AES-256-GCM for voice payloads on MQTT. Output is `nonce(12) || ciphertext+tag(16)`; [aad] (the topic) is
 * authenticated so a payload cannot be replayed onto another channel's topic.
 */
interface VoiceCipher {
    fun seal(key: ByteString, plaintext: ByteString, aad: ByteString): ByteString

    /** Null when the key is wrong or the payload was tampered with. */
    fun open(key: ByteString, sealed: ByteString, aad: ByteString): ByteString?
}

/** Null where there is no AES-GCM implementation yet (iOS); voice over MQTT is then unavailable. */
expect fun platformVoiceCipher(): VoiceCipher?

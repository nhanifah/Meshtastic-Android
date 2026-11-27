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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.voice

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.model.Channel

/**
 * Where voice goes on the radio's own MQTT server (the one entered under Settings → MQTT; voice adds no second server).
 * Topics live beside the firmware's `<root>/2/e/…` tree:
 * - channel: `<root>/voice/1/c/<channel id>`, where the id is a hash of the channel's name and key, so neither the name
 *   nor the key shows in the topic and two channels that merely share a name do not hear each other;
 * - direct message: `<root>/voice/1/d/<!nodeid of the recipient>`.
 */
object VoiceMqttTopics {
    const val DEFAULT_ROOT = "msh"

    fun root(configuredRoot: String): String = configuredRoot.trim().trimEnd('/').ifEmpty { DEFAULT_ROOT }

    fun channelId(channel: Channel): String = Buffer()
        .write("meshtastic-voice-topic".encodeUtf8())
        .write(channel.name.encodeUtf8())
        .write(channel.psk)
        .readByteString()
        .sha256()
        .hex()
        .take(16)

    fun channel(root: String, channel: Channel): String = "${root(root)}/voice/1/c/${channelId(channel)}"

    fun direct(root: String, nodeNum: Int): String = "${root(root)}/voice/1/d/${nodeId(nodeNum)}"

    fun nodeId(nodeNum: Int): String = "!" + nodeNum.toUInt().toString(16).padStart(8, '0')
}

/**
 * Keys for voice over MQTT. The broker sees only ciphertext: each payload is AES-256-GCM under a key derived (HKDF-
 * SHA256) from the channel's PSK, so only holders of that channel key can listen. Direct messages use the key of the
 * channel the conversation is on: the phone does not hold the radio's private PKI key, so this is channel-grade, not
 * PKI-grade, privacy.
 */
object VoiceKeys {
    private val SALT = "meshtastic-voice-v1".encodeUtf8()

    /** A channel whose key is empty or one of the published defaults (`AQ==` and friends) is effectively public. */
    fun isPublic(channel: Channel): Boolean = channel.settings.psk.size <= 1

    fun channelKey(channel: Channel): ByteString = hkdfSha256(
        ikm = channel.psk.ifEmptyUse("cleartext".encodeUtf8()),
        info = "channel:".encodeUtf8(),
        length = 32,
    )

    /** RFC 5869 HKDF with SHA-256, built on okio's HMAC so it runs on every target. */
    internal fun hkdfSha256(ikm: ByteString, info: ByteString, length: Int, salt: ByteString = SALT): ByteString {
        val prk = ikm.hmacSha256(salt)
        val out = Buffer()
        var block = ByteString.EMPTY
        var counter = 1
        while (out.size < length) {
            block = Buffer().write(block).write(info).writeByte(counter).readByteString().hmacSha256(prk)
            out.write(block)
            counter++
        }
        return out.readByteString(length.toLong())
    }

    private fun ByteString.ifEmptyUse(other: ByteString) = if (size == 0) other else this
}

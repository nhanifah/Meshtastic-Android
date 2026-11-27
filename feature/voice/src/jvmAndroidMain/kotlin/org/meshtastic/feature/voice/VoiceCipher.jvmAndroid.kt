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

import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

private object JcaVoiceCipher : VoiceCipher {
    private val random = SecureRandom()

    override fun seal(key: ByteString, plaintext: ByteString, aad: ByteString): ByteString {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray())
        return (nonce + cipher.doFinal(plaintext.toByteArray())).toByteString()
    }

    override fun open(key: ByteString, sealed: ByteString, aad: ByteString): ByteString? {
        if (sealed.size <= NONCE_BYTES + TAG_BITS / 8) return null
        val bytes = sealed.toByteArray()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(), "AES"),
                GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
            )
            cipher.updateAAD(aad.toByteArray())
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES).toByteString()
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}

actual fun platformVoiceCipher(): VoiceCipher? = JcaVoiceCipher

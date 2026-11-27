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

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.codec2.Codec2
import org.meshtastic.core.model.Channel
import org.meshtastic.proto.ChannelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNativeTest {
    private fun channel(name: String, psk: ByteArray) = Channel(
        ChannelSettings.Builder()
            .also {
                it.name = name
                it.psk = psk.toByteString()
            }
            .build(),
    )

    @Test
    fun frameShapesMatchTheNativeCodec() {
        VoiceCodec.entries.forEach { codec ->
            Codec2.open(codec.mode).use { c2 ->
                assertEquals(c2.samplesPerFrame, codec.samplesPerFrame, "$codec samples")
                assertEquals(c2.bytesPerFrame, codec.bytesPerFrame, "$codec bytes")
            }
        }
    }

    @Test
    fun aRecordedNoteSurvivesTheWholePipeline() {
        val codec = VoiceCodec.C2_1300
        val pcm = ShortArray(codec.samplesPerFrame * 75) { (kotlin.math.sin(it / 9.0) * 5000).toInt().toShort() }
        val frames = Codec2.open(codec.mode).use { it.encode(pcm) }.toByteString()
        val clip = VoiceClip.fromFrames(origin = 42, clipId = 7, codec = codec, frames = frames)
        val wire = clip.loraPackets().map { VoiceWire.decode(VoiceWire.encode(it))!! }
        val rebuilt = VoiceReassembler().let { r -> wire.shuffled().mapNotNull { r.accept(it, 0) }.single() }
        val stored = assertNotNull(VoiceClip.fromStored(rebuilt.toStored()))
        assertEquals(3_000, stored.durationMs)
        val frameCount = stored.segments.sumOf { (it as VoiceClip.Segment.Audio).frames.size / codec.bytesPerFrame }
        assertEquals(75, frameCount)
        val audio = Codec2.open(codec.mode).use { c2 -> c2.decode(stored.wholePacket().frames.toByteArray()) }
        assertEquals(pcm.size, audio.size)
    }

    @Test
    fun mqttPayloadsAreSealedPerChannel() {
        val cipher = assertNotNull(platformVoiceCipher())
        val channel = channel("team", ByteArray(32) { it.toByte() })
        val other = channel("team", ByteArray(32) { 1 })
        val topic = VoiceMqttTopics.channel("msh", channel).encodeUtf8()
        val plain = "voice".encodeUtf8()
        val sealed = cipher.seal(VoiceKeys.channelKey(channel), plain, topic)
        assertEquals(plain, cipher.open(VoiceKeys.channelKey(channel), sealed, topic))
        assertNull(cipher.open(VoiceKeys.channelKey(other), sealed, topic), "another key cannot read it")
        assertNull(cipher.open(VoiceKeys.channelKey(channel), sealed, "msh/other".encodeUtf8()), "bound to its topic")
        assertNotEquals(VoiceMqttTopics.channel("msh", channel), VoiceMqttTopics.channel("msh", other))
        assertTrue(VoiceKeys.isPublic(Channel.default))
        assertTrue(!VoiceKeys.isPublic(channel))
        assertEquals("msh/voice/1/d/!0000002a", VoiceMqttTopics.direct("msh/", 42))
    }
}

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
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceProtocolTest {
    private fun frames(codec: VoiceCodec, count: Int, fill: Int = 1): ByteString =
        ByteArray(count * codec.bytesPerFrame) { (it + fill).toByte() }.toByteString()

    private fun preset(p: ModemPreset) = LoraModulation.of(
        LoRaConfig.Builder()
            .also {
                it.use_preset = true
                it.modem_preset = p
            }
            .build(),
    )

    @Test
    fun headerRoundTripsEveryField() {
        val packet =
            VoicePacket(
                type = VoicePacketType.NOTE_CHUNK,
                codec = VoiceCodec.C2_1300,
                origin = 0xDEADBEEF.toInt(),
                streamId = -7,
                seq = 65_000,
                total = 65_001,
                frames = frames(VoiceCodec.C2_1300, 3),
                last = true,
                forwarded = true,
                viaMqtt = true,
            )
        val encoded = VoiceWire.encode(packet)
        assertEquals(VoiceWire.HEADER_SIZE + 21, encoded.size)
        assertEquals(packet, VoiceWire.decode(encoded))
    }

    @Test
    fun foreignAndBrokenPayloadsAreNotVoice() {
        assertNull(VoiceWire.decode("MRT1 seq 5 int 15".encodeUtf8()))
        assertNull(VoiceWire.decode(ByteString.EMPTY))
        val ok =
            VoiceWire.encode(
                VoicePacket(VoicePacketType.LIVE_FRAMES, VoiceCodec.C2_700C, 1, 2, 0, 0, frames(VoiceCodec.C2_700C, 2)),
            )
        // A truncated frame is refused rather than decoded into noise.
        assertNull(VoiceWire.decode(ok.substring(0, ok.size - 1)))
        assertNotNull(VoiceWire.decode(ok))
    }

    @Test
    fun everyLoraPacketFitsTheSendLimit() {
        VoiceCodec.entries.forEach { codec ->
            val perPacket = VoiceWire.loraFramesPerPacket(codec)
            assertTrue(perPacket > 0)
            val clip = VoiceClip.fromFrames(1, 2, codec, frames(codec, perPacket * 3 + 1))
            assertEquals(4, clip.chunks.size)
            clip.loraPackets().forEach { assertTrue(VoiceWire.encode(it).size <= VoiceWire.LORA_MAX_PACKET) }
        }
    }

    @Test
    fun storedClipRoundTripsWithGaps() {
        val clip = VoiceClip.fromFrames(9, 10, VoiceCodec.C2_1300, frames(VoiceCodec.C2_1300, 70))
        val gappy = clip.copy(chunks = clip.chunks.toMutableList().also { it[1] = null })
        val back = assertNotNull(VoiceClip.fromStored(gappy.toStored()))
        assertEquals(gappy.chunks, back.chunks)
        assertEquals(1, back.missingChunks)
        assertEquals(clip.durationMs, back.durationMs) // the gap keeps its length
        assertFalse(VoiceClip.isStored("hello".encodeUtf8()))
    }

    @Test
    fun reassemblerHandlesReorderDuplicatesAndLoss() {
        val clip = VoiceClip.fromFrames(5, 77, VoiceCodec.C2_1300, frames(VoiceCodec.C2_1300, 100))
        val packets = clip.loraPackets()
        val r = VoiceReassembler()
        var done: VoiceClip? = null
        (packets.reversed() + packets.first()).forEach { p -> r.accept(p, 0)?.let { done = it } }
        assertEquals(clip.chunks, done?.chunks)
        assertNull(r.accept(packets.first(), 1), "a finished note is not built twice")

        val lossy = VoiceReassembler(afterLastChunkMs = 1_000)
        val other = VoiceClip.fromFrames(5, 78, VoiceCodec.C2_1300, frames(VoiceCodec.C2_1300, 100))
        other.loraPackets().filterIndexed { i, _ -> i != 1 }.forEach { assertNull(lossy.accept(it, 0)) }
        assertTrue(lossy.expire(999).isEmpty())
        val partial = lossy.expire(1_000).single()
        assertEquals(1, partial.missingChunks)
        assertEquals(0, lossy.pendingCount)
    }

    @Test
    fun reassemblerIsBounded() {
        val r = VoiceReassembler(maxPending = 2)
        repeat(5) { id ->
            val c = VoiceClip.fromFrames(1, id, VoiceCodec.C2_700C, frames(VoiceCodec.C2_700C, 200))
            r.accept(c.loraPackets().first(), 0)
        }
        assertEquals(2, r.pendingCount)
    }

    @Test
    fun wholeNotesFromMqttArriveInOnePacket() {
        val clip = VoiceClip.fromFrames(3, 4, VoiceCodec.C2_2400, frames(VoiceCodec.C2_2400, 90))
        val back = assertNotNull(VoiceReassembler().accept(clip.wholePacket(), 0))
        assertEquals(clip.chunks, back.chunks)
        assertTrue(back.viaMqtt)
    }

    @Test
    fun airtimeMatchesTheSemtechCalculator() {
        // A 32-byte LoRa payload (22 bytes of Meshtastic overhead + 10), preamble 16, explicit header, CRC on:
        // LongFast SF11/250 kHz/4:5 → 20.25 + 38 symbols of 8.192 ms = 477.18 ms.
        assertEquals(477.18, VoiceAirtime.packetMs(preset(ModemPreset.LONG_FAST), 32 - 22), 0.01)
        // ShortFast SF7/250 kHz/4:5 → 20.25 + 58 symbols of 0.512 ms = 40.06 ms.
        assertEquals(40.06, VoiceAirtime.packetMs(preset(ModemPreset.SHORT_FAST), 32 - 22), 0.01)
        // LongSlow SF12/125 kHz/4:8 uses low-data-rate optimisation (32.768 ms symbols).
        assertTrue(preset(ModemPreset.LONG_SLOW).symbolMs > 16.0)
    }

    @Test
    fun liveOverLoraOnlyOnFastPresets() {
        val codec = VoiceCodec.C2_1300
        assertIs<LoraVoiceBudget.Ok>(LoraVoicePolicy.forLive(preset(ModemPreset.SHORT_FAST), codec))
        assertIs<LoraVoiceBudget.Ok>(LoraVoicePolicy.forLive(preset(ModemPreset.SHORT_TURBO), codec))
        assertIs<LoraVoiceBudget.TooSlow>(LoraVoicePolicy.forLive(preset(ModemPreset.LONG_FAST), codec))
        assertIs<LoraVoiceBudget.ChannelBusy>(
            LoraVoicePolicy.forLive(preset(ModemPreset.SHORT_FAST), codec, airUtilTx = 9f),
        )
    }

    @Test
    fun routerPrefersMqttAndQueuesWhenNothingIsUp() {
        val both = VoiceLinks(mqttReady = true, radioReady = true)
        val loraOnly = VoiceLinks(mqttReady = false, radioReady = true)
        val none = VoiceLinks(mqttReady = false, radioReady = false)
        assertEquals(NoteRoute.Send(mqtt = true, lora = false), VoiceRouter.note(both, VoiceNoteTransport.AUTO))
        assertEquals(NoteRoute.Send(mqtt = false, lora = true), VoiceRouter.note(loraOnly, VoiceNoteTransport.AUTO))
        assertEquals(NoteRoute.Send(mqtt = true, lora = true), VoiceRouter.note(both, VoiceNoteTransport.BOTH))
        assertEquals(NoteRoute.Queue, VoiceRouter.note(none, VoiceNoteTransport.AUTO))
        assertEquals(NoteRoute.Queue, VoiceRouter.note(loraOnly, VoiceNoteTransport.MQTT_ONLY))

        val slow = LoraVoiceBudget.TooSlow(2_000)
        assertEquals(LiveRoute.Mqtt, VoiceRouter.live(both, slow), "MQTT works on any preset")
        assertEquals(
            LiveRoute.Unavailable(LiveUnavailableReason.LORA_TOO_SLOW),
            VoiceRouter.live(loraOnly, slow),
        )
        assertEquals(LiveRoute.Lora, VoiceRouter.live(loraOnly, LoraVoiceBudget.Ok(300)))
    }

    @Test
    fun jitterBufferWaitsThenFillsGapsWithSilence() {
        val codec = VoiceCodec.C2_1300
        fun live(seq: Int) = VoicePacket(VoicePacketType.LIVE_FRAMES, codec, 1, 1, seq, 0, frames(codec, 5, seq))
        val jb = LiveJitterBuffer(codec, targetDelayMs = 400)
        jb.push(live(0), 0)
        assertEquals(LiveJitterBuffer.Next.Waiting, jb.pull(0)) // 200 ms buffered, target 400
        jb.push(live(2), 10)
        assertIs<LiveJitterBuffer.Next.Audio>(jb.pull(10))
        assertEquals(LiveJitterBuffer.Next.Silence(5), jb.pull(10)) // seq 1 lost, seq 2 waiting
        assertIs<LiveJitterBuffer.Next.Audio>(jb.pull(10))
        jb.push(VoicePacket(VoicePacketType.LIVE_END, codec, 1, 1, 3, 0, ByteString.EMPTY), 20)
        assertEquals(LiveJitterBuffer.Next.Ended, jb.pull(20))
    }
}

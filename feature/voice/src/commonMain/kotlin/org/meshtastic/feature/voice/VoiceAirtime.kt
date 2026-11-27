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

import org.meshtastic.core.model.ChannelOption
import org.meshtastic.core.model.RegionInfo
import org.meshtastic.proto.Config.LoRaConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/** The LoRa modulation a packet goes out with. [bandwidthKhz] already includes the 2.4 GHz widening. */
data class LoraModulation(val spreadingFactor: Int, val bandwidthKhz: Double, val codingRate: Int) {
    val symbolMs: Double
        get() = 2.0.pow(spreadingFactor) / bandwidthKhz

    companion object {
        /** What the radio will use for [config], mirroring firmware `RadioInterface::applyModemConfig`. */
        fun of(config: LoRaConfig): LoraModulation {
            val wide = RegionInfo.fromRegionCode(config.region)?.wideLora == true
            val widen = if (wide) 3.25 else 1.0
            val preset =
                if (config.use_preset) ChannelOption.from(config.modem_preset) ?: ChannelOption.DEFAULT else null
            return if (preset != null) {
                val cr = if (config.coding_rate in (preset.codingRate + 1)..8) config.coding_rate else preset.codingRate
                LoraModulation(preset.spreadingFactor, preset.bandwidth * 1000.0 * widen, cr)
            } else {
                val bw =
                    when (config.bandwidth) {
                        31 -> 31.25
                        62 -> 62.5
                        200 -> 203.125
                        400 -> 406.25
                        800 -> 812.5
                        1600 -> 1625.0
                        0 -> 250.0
                        else -> config.bandwidth.toDouble()
                    }
                LoraModulation(
                    spreadingFactor = config.spread_factor.takeIf { it in 5..12 } ?: 11,
                    bandwidthKhz = bw,
                    codingRate = config.coding_rate.takeIf { it in 5..8 } ?: 5,
                )
            }
        }
    }
}

/** Time-on-air arithmetic for voice over LoRa (Semtech AN1200.13, explicit header, CRC on, 16-symbol preamble). */
object VoiceAirtime {
    /** Meshtastic's over-the-air packet header, sent before the encrypted `Data`. */
    private const val MESH_HEADER_BYTES = 16

    /** `Data.portnum` (PRIVATE_APP = 256, a 2-byte varint) and the payload field's tag and length. */
    private const val DATA_OVERHEAD_BYTES = 6
    private const val PREAMBLE_SYMBOLS = 16

    /** Firmware turns on low-data-rate optimisation once a symbol lasts longer than this. */
    private const val LDRO_SYMBOL_MS = 16.0

    /** Milliseconds on air for one packet carrying [voiceBytes] of [VoiceWire] payload. */
    fun packetMs(modulation: LoraModulation, voiceBytes: Int): Double {
        val sf = modulation.spreadingFactor
        val de = if (modulation.symbolMs > LDRO_SYMBOL_MS) 1 else 0
        val pl = MESH_HEADER_BYTES + DATA_OVERHEAD_BYTES + voiceBytes
        val cr = modulation.codingRate - 4
        val payloadSymbols = 8 + max(ceil((8.0 * pl - 4 * sf + 28 + 16) / (4.0 * (sf - 2 * de))) * (cr + 4), 0.0)
        return (PREAMBLE_SYMBOLS + 4.25 + payloadSymbols) * modulation.symbolMs
    }

    /** Airtime of a full LoRa voice packet divided by the audio it carries; above 1.0 a stream cannot keep up. */
    fun streamRatio(modulation: LoraModulation, codec: VoiceCodec): Double {
        val frames = VoiceWire.loraFramesPerPacket(codec)
        val bytes = VoiceWire.HEADER_SIZE + frames * codec.bytesPerFrame
        return packetMs(modulation, bytes) / (frames * codec.frameMs)
    }

    /** Total airtime for a whole [clip] sent over LoRa once (no relays, no retries). */
    fun clipMs(modulation: LoraModulation, clip: VoiceClip): Double =
        clip.loraPackets().sumOf { packetMs(modulation, VoiceWire.HEADER_SIZE + it.frames.size) }
}

/** Why voice over LoRa is or is not allowed right now. */
sealed interface LoraVoiceBudget {
    data class Ok(val sendMs: Long) : LoraVoiceBudget

    /** Allowed, but slow enough that the user should be told how long it takes. */
    data class Slow(val sendMs: Long) : LoraVoiceBudget

    /** The preset is too slow for this clip or for live audio. */
    data class TooSlow(val sendMs: Long) : LoraVoiceBudget

    /** The local radio has used up its share of airtime (duty cycle or a busy channel). */
    data class ChannelBusy(val utilizationPercent: Float) : LoraVoiceBudget
}

object LoraVoicePolicy {
    /** A voice note taking longer than this on air gets a warning. */
    const val SLOW_NOTE_MS = 20_000L

    /** A voice note taking longer than this on air is refused. */
    const val MAX_NOTE_MS = 90_000L

    /** Live audio over LoRa needs headroom for the radio's own traffic, so the stream may use at most 60 % of it. */
    const val MAX_LIVE_RATIO = 0.6

    /** Firmware stops sending above its duty-cycle limit; we stop well before that. */
    const val MAX_AIR_UTIL_TX_PERCENT = 8f
    const val MAX_CHANNEL_UTIL_PERCENT = 50f

    fun forNote(
        modulation: LoraModulation,
        clip: VoiceClip,
        airUtilTx: Float = 0f,
        channelUtil: Float = 0f,
    ): LoraVoiceBudget {
        busy(airUtilTx, channelUtil)?.let {
            return it
        }
        val ms = VoiceAirtime.clipMs(modulation, clip).toLong()
        return when {
            ms > MAX_NOTE_MS -> LoraVoiceBudget.TooSlow(ms)
            ms > SLOW_NOTE_MS -> LoraVoiceBudget.Slow(ms)
            else -> LoraVoiceBudget.Ok(ms)
        }
    }

    fun forLive(
        modulation: LoraModulation,
        codec: VoiceCodec,
        airUtilTx: Float = 0f,
        channelUtil: Float = 0f,
    ): LoraVoiceBudget {
        busy(airUtilTx, channelUtil)?.let {
            return it
        }
        val ratio = VoiceAirtime.streamRatio(modulation, codec)
        val perSecond = (ratio * 1000).toLong()
        return if (ratio <= MAX_LIVE_RATIO) LoraVoiceBudget.Ok(perSecond) else LoraVoiceBudget.TooSlow(perSecond)
    }

    private fun busy(airUtilTx: Float, channelUtil: Float): LoraVoiceBudget? = when {
        airUtilTx >= MAX_AIR_UTIL_TX_PERCENT -> LoraVoiceBudget.ChannelBusy(airUtilTx)
        channelUtil >= MAX_CHANNEL_UTIL_PERCENT -> LoraVoiceBudget.ChannelBusy(channelUtil)
        else -> null
    }
}

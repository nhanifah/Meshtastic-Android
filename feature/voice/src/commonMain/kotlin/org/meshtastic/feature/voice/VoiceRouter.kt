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

/** How the user wants voice notes to travel (Settings → Voice). */
enum class VoiceNoteTransport {
    /** MQTT when the server is reachable, LoRa otherwise. */
    AUTO,
    MQTT_ONLY,
    LORA_ONLY,

    /** Both at once: MQTT for people online, LoRa for the local mesh. */
    BOTH,
}

/** What the transports can do right now. */
data class VoiceLinks(
    /** The voice MQTT client is connected to the radio's MQTT server. */
    val mqttReady: Boolean,
    /** A radio is connected to this device. */
    val radioReady: Boolean,
)

sealed interface NoteRoute {
    data class Send(val mqtt: Boolean, val lora: Boolean) : NoteRoute

    /** Nothing can carry it now; keep it in the outbox. */
    data object Queue : NoteRoute
}

enum class LiveUnavailableReason {
    /** No MQTT server reachable and no radio connected. */
    NO_LINK,

    /** Only LoRa is available and the preset is too slow for live audio. */
    LORA_TOO_SLOW,

    /** Only LoRa is available and the radio has used its airtime share. */
    LORA_BUSY,
}

sealed interface LiveRoute {
    data object Mqtt : LiveRoute

    data object Lora : LiveRoute

    data class Unavailable(val reason: LiveUnavailableReason) : LiveRoute
}

/** The transport decisions, kept free of I/O so every case is tested. */
object VoiceRouter {
    fun note(links: VoiceLinks, preference: VoiceNoteTransport): NoteRoute {
        val (mqtt, lora) =
            when (preference) {
                VoiceNoteTransport.AUTO -> if (links.mqttReady) true to false else false to links.radioReady
                VoiceNoteTransport.MQTT_ONLY -> links.mqttReady to false
                VoiceNoteTransport.LORA_ONLY -> false to links.radioReady
                VoiceNoteTransport.BOTH -> links.mqttReady to links.radioReady
            }
        return if (mqtt || lora) NoteRoute.Send(mqtt = mqtt, lora = lora) else NoteRoute.Queue
    }

    /**
     * Live audio prefers MQTT, on any preset. Over LoRa it needs [loraBudget] to be [LoraVoiceBudget.Ok]; it never
     * falls back to LoRa in the middle of a burst (the caller ends the burst instead).
     */
    fun live(links: VoiceLinks, loraBudget: LoraVoiceBudget?): LiveRoute = when {
        links.mqttReady -> LiveRoute.Mqtt
        !links.radioReady -> LiveRoute.Unavailable(LiveUnavailableReason.NO_LINK)
        loraBudget is LoraVoiceBudget.Ok -> LiveRoute.Lora
        loraBudget is LoraVoiceBudget.ChannelBusy -> LiveRoute.Unavailable(LiveUnavailableReason.LORA_BUSY)
        else -> LiveRoute.Unavailable(LiveUnavailableReason.LORA_TOO_SLOW)
    }
}

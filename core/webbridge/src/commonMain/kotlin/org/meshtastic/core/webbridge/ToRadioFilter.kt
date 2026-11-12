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
package org.meshtastic.core.webbridge

import org.meshtastic.proto.ToRadio

/** What the bridge does with one `ToRadio` frame a browser sent. */
sealed interface ToRadioDecision {
    /** Send the original bytes to the radio unchanged. */
    data object Forward : ToRadioDecision

    /** Answer locally with a config dump for [nonce]; the radio must never see this request. */
    data class AnswerConfig(val nonce: Int) : ToRadioDecision

    /** Swallow the frame. [reason] is a fixed label for diagnostics and never carries frame contents. */
    data class Drop(val reason: String) : ToRadioDecision
}

/**
 * Decides what a browser may send to the radio the phone owns. It is an allowlist: only mesh packets pass through.
 *
 * - `want_config_id` is answered by the phone. A second `want_config` on the radio's live session crashes firmware.
 * - `disconnect` would end the phone's own session, and `heartbeat` is redundant because the phone already keeps the
 *   link alive. Interleaving a second sender's heartbeats also risks the firmware's single-slot duplicate-write filter.
 * - MQTT proxy and XMODEM traffic belong to the phone's own session, so a second client must not inject into them.
 */
object ToRadioFilter {

    fun decide(bytes: ByteArray): ToRadioDecision {
        val toRadio =
            runCatching { ToRadio.ADAPTER.decode(bytes) }.getOrNull() ?: return ToRadioDecision.Drop("undecodable")
        val wantConfigId = toRadio.want_config_id
        return when {
            toRadio.packet != null -> ToRadioDecision.Forward
            wantConfigId != null -> ToRadioDecision.AnswerConfig(wantConfigId)
            toRadio.disconnect != null -> ToRadioDecision.Drop("disconnect")
            toRadio.heartbeat != null -> ToRadioDecision.Drop("heartbeat")
            toRadio.mqttClientProxyMessage != null -> ToRadioDecision.Drop("mqtt-proxy")
            toRadio.xmodemPacket != null -> ToRadioDecision.Drop("xmodem")
            else -> ToRadioDecision.Drop("unsupported")
        }
    }
}

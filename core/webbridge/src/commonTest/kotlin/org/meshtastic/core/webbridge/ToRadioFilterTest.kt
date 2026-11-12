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

import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToRadioFilterTest {

    private fun decide(build: ToRadio.Builder.() -> Unit) =
        ToRadioFilter.decide(ToRadio.Builder().apply(build).build().encode())

    @Test
    fun `mesh packets are forwarded`() {
        assertEquals(ToRadioDecision.Forward, decide { packet = MeshPacket.Builder().also { it.to = 1 }.build() })
    }

    @Test
    fun `want_config is answered locally and never forwarded`() {
        assertEquals(ToRadioDecision.AnswerConfig(4242), decide { want_config_id = 4242 })
    }

    @Test
    fun `disconnect and heartbeat are dropped`() {
        assertIs<ToRadioDecision.Drop>(decide { disconnect = true })
        assertIs<ToRadioDecision.Drop>(decide { heartbeat = Heartbeat.Builder().build() })
    }

    @Test
    fun `garbage is dropped`() {
        assertIs<ToRadioDecision.Drop>(ToRadioFilter.decide(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }
}

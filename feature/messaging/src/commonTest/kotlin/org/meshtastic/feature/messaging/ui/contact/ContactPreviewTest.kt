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
package org.meshtastic.feature.messaging.ui.contact

import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactPreviewTest {
    @Test
    fun voiceNoteGetsALabelInsteadOfNull() {
        val packet = DataPacket(bytes = byteArrayOf(1, 2, 3).toByteString(), dataType = PortNum.AUDIO_APP.value)
        assertEquals("🎤 Voice note", previewOf(packet, "Voice note"))
    }

    @Test
    fun textMessageKeepsItsText() {
        assertEquals("hi", previewOf(DataPacket(to = NodeAddress.ID_BROADCAST, channel = 0, text = "hi"), "Voice note"))
    }

    @Test
    fun packetWithoutTextHasNoPreview() {
        assertNull(previewOf(DataPacket(bytes = null, dataType = 1, time = 0L, channel = 0), "Voice note"))
    }
}

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
package org.meshtastic.feature.rangetest

import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.ChannelSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RangeTestChannelsTest {

    private fun channel(name: String, key: String = name) = ChannelSettings.Builder()
        .also { wb ->
            wb.name = name
            wb.psk = key.encodeUtf8()
        }
        .build()

    private val primary = channel("")
    private val other = channel("PIN")

    @Test
    fun addsTheChannelToTheFirstFreeSlotWhenMissing() {
        val plan = RangeTestChannels.withSingleChannel(listOf(primary, other), channel("rangeTest", "a"))
        assertEquals(listOf(primary, other, channel("rangeTest", "a")), plan.settings)
        assertEquals(2, plan.index)
    }

    @Test
    fun joiningWithANewKeyOverwritesTheSameNamedSlotInsteadOfAddingASecond() {
        val old = listOf(primary, channel("rangeTest", "old"), other)
        val plan = RangeTestChannels.withSingleChannel(old, channel("rangeTest", "new"))
        assertEquals(listOf(primary, channel("rangeTest", "new"), other), plan.settings)
        assertEquals(1, plan.index)
    }

    @Test
    fun leftoverDuplicatesAreDroppedAndLaterChannelsMoveUp() {
        val old = listOf(primary, other, channel("rangeTest", "a"), channel("rangeTest", "b"), channel("X"))
        val plan = RangeTestChannels.withSingleChannel(old, channel("rangeTest", "a"))
        assertEquals(listOf(primary, other, channel("rangeTest", "a"), channel("X")), plan.settings)
        assertEquals(2, plan.index)
    }

    @Test
    fun anExistingSingleChannelIsLeftUnchanged() {
        val old = listOf(primary, channel("rangeTest", "a"))
        val plan = RangeTestChannels.withSingleChannel(old, channel("rangeTest", "a"))
        assertEquals(old, plan.settings)
        assertEquals(1, plan.index)
    }
}

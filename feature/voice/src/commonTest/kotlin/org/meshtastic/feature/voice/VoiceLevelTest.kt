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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLevelTest {
    @Test
    fun quietSpeechIsBroughtUpButCapped() {
        val quiet = listOf(ShortArray(100) { if (it % 2 == 0) 2_000 else -2_000 })
        val gain = VoiceLevel.gainFor(quiet)
        assertTrue(gain > 1f && gain <= 6f, "gain $gain")
        val out = VoiceLevel.apply(quiet[0], gain)
        assertTrue(out.maxOf { it.toInt() } > 10_000)
    }

    @Test
    fun loudSpeechAndSilenceAreLeftAlone() {
        assertEquals(1f, VoiceLevel.gainFor(listOf(ShortArray(10) { 30_000 })))
        assertEquals(1f, VoiceLevel.gainFor(listOf(ShortArray(10))))
    }

    @Test
    fun applyNeverWrapsAround() {
        val out = VoiceLevel.apply(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), 6f)
        assertEquals(Short.MIN_VALUE, out[0])
        assertEquals(Short.MAX_VALUE, out[1])
    }
}

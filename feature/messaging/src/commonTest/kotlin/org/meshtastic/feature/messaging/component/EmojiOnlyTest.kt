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
package org.meshtastic.feature.messaging.component

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiOnlyTest {
    @Test
    fun singleAndFewEmojiCount() {
        assertEquals(1, emojiOnlyCount("👍"))
        assertEquals(1, emojiOnlyCount(" ❤️ "))
        assertEquals(2, emojiOnlyCount("😂😂"))
        assertEquals(3, emojiOnlyCount("🔥 ✅ 🐺"))
    }

    @Test
    fun joinedSequencesAndFlagsCountOnce() {
        assertEquals(1, emojiOnlyCount("👍🏽"))
        assertEquals(1, emojiOnlyCount("👨‍👩‍👧"))
        assertEquals(1, emojiOnlyCount("🇮🇩"))
        assertEquals(1, emojiOnlyCount("🏍️"))
    }

    @Test
    fun textOrTooManyIsNotASticker() {
        assertEquals(0, emojiOnlyCount("ok 👍"))
        assertEquals(0, emojiOnlyCount("👍👍👍👍"))
        assertEquals(0, emojiOnlyCount(""))
        assertEquals(0, emojiOnlyCount("123"))
        assertEquals(0, emojiOnlyCount(":)"))
    }

    @Test
    fun everyComposerStickerIsDrawnBig() {
        COMPOSER_STICKERS.forEach { assertEquals(1, emojiOnlyCount(it), "sticker $it") }
    }
}

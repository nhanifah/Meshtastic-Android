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

package org.meshtastic.feature.messaging.component

/** The stickers offered in the composer: emoji sent on their own, so every Meshtastic client still shows them. */
internal val COMPOSER_STICKERS: List<String> =
    listOf(
        "👍",
        "👌",
        "🙏",
        "👏",
        "🙌",
        "💪",
        "🤝",
        "👋",
        "😂",
        "🤣",
        "😊",
        "😍",
        "😎",
        "🤔",
        "😮",
        "😴",
        "😢",
        "😭",
        "😡",
        "🥳",
        "❤️",
        "🔥",
        "✅",
        "❌",
        "⚠️",
        "🆘",
        "📡",
        "📶",
        "🔋",
        "📍",
        "🗺️",
        "🐺",
        "🚗",
        "🏍️",
        "⛰️",
        "🌧️",
        "☕",
        "🍜",
        "🎉",
        "💯",
    )

/**
 * How many emoji [text] holds if it holds nothing else (1 to [max]), or 0. WhatsApp-style: a message of one to three
 * emoji is drawn large without a bubble, which is also what makes a sticker look like one.
 */
internal fun emojiOnlyCount(text: String, max: Int = 3): Int {
    var count = 0
    var valid = text.isNotBlank()
    var joinNext = false
    var i = 0
    while (valid && i < text.length) {
        val cp = codePointAt(text, i)
        i += if (cp > 0xFFFF) 2 else 1
        when {
            cp == ZWJ -> joinNext = true

            cp == VARIATION_SELECTOR || cp == KEYCAP || cp in SKIN_TONES || cp in TAGS -> Unit

            cp.toChar().isWhitespace() && cp <= 0xFFFF -> joinNext = false

            // Before the generic range check: a flag is two regional indicators and counts once.
            cp in REGIONAL_INDICATORS -> {
                if (!joinNext) count++
                if (i < text.length && codePointAt(text, i) in REGIONAL_INDICATORS) i += 2
                joinNext = false
            }

            isEmojiBase(cp) -> {
                if (!joinNext) count++
                joinNext = false
            }

            else -> valid = false
        }
        if (count > max) valid = false
    }
    return if (valid) count else 0
}

private const val ZWJ = 0x200D
private const val VARIATION_SELECTOR = 0xFE0F
private const val KEYCAP = 0x20E3
private val SKIN_TONES = 0x1F3FB..0x1F3FF
private val TAGS = 0xE0020..0xE007F
private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

private fun isEmojiBase(cp: Int): Boolean = cp in 0x1F000..0x1FAFF ||
    cp in 0x2600..0x27BF ||
    cp in 0x2300..0x23FF ||
    cp in 0x2B00..0x2BFF ||
    cp in 0x2190..0x21FF ||
    cp == 0x00A9 ||
    cp == 0x00AE ||
    cp == 0x203C ||
    cp == 0x2049 ||
    cp == 0x2122 ||
    cp == 0x2139 ||
    cp == 0x3030 ||
    cp == 0x303D ||
    cp == 0x3297 ||
    cp == 0x3299

private fun codePointAt(s: String, i: Int): Int {
    val high = s[i]
    if (high.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}

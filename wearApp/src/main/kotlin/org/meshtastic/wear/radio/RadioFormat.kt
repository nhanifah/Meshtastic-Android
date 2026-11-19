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
package org.meshtastic.wear.radio

import java.util.Locale

/** "87% 4.05V", "Powered 4.20V" (level 101 is external power), or "--" when the radio reports nothing. */
fun battery(level: Int?, voltage: Float?): String {
    val levelText =
        when {
            level == null || level <= 0 -> null
            level > MAX_BATTERY_PERCENT -> "Powered"
            else -> "$level%"
        }
    val voltageText = voltage?.takeIf { it > 0f }?.let { "${fixed(it.toDouble(), 2)}V" }
    return listOfNotNull(levelText, voltageText).joinToString(" ").ifEmpty { "--" }
}

/** "3d 4h", "2h 05m", "12m", "45s". */
fun duration(seconds: Long): String = when {
    seconds >= SECONDS_PER_DAY -> "${seconds / SECONDS_PER_DAY}d ${seconds % SECONDS_PER_DAY / SECONDS_PER_HOUR}h"

    seconds >= SECONDS_PER_HOUR ->
        "${seconds / SECONDS_PER_HOUR}h ${(seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toString().padStart(
            2,
            '0',
        )}m"

    seconds >= SECONDS_PER_MINUTE -> "${seconds / SECONDS_PER_MINUTE}m"

    else -> "${seconds.coerceAtLeast(0)}s"
}

/** "12s", "5m", "3h", "2d" between [thenMillis] and [nowMillis]; null when the time is unknown. */
fun ago(nowMillis: Long, thenMillis: Long): String? {
    if (thenMillis <= 0L) return null
    val seconds = ((nowMillis - thenMillis) / MS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
        else -> "${seconds / SECONDS_PER_DAY}d"
    }
}

/** LONG_FAST → LongFast, CLIENT_MUTE → ClientMute. */
fun String.toPresetName(): String =
    split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

internal fun percent(value: Float): String = "${fixed(value.toDouble(), 1)}%"

internal fun fixed(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

private const val MAX_BATTERY_PERCENT = 100
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L

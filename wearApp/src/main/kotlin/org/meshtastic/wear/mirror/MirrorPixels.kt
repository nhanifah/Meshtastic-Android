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
package org.meshtastic.wear.mirror

import org.meshtastic.core.repository.MirrorFormat
import org.meshtastic.core.repository.MirrorFrame
import org.meshtastic.core.repository.MirrorPalette

/**
 * Turns one device framebuffer into ARGB_8888 pixels, row-major, ready for `Bitmap.createBitmap`. A palette applies
 * only when the frame references it by signature; otherwise a mono frame is white on black, as on the OLED.
 */
fun mirrorArgb(frame: MirrorFrame, palette: MirrorPalette?): IntArray {
    val active = palette?.takeIf { frame.paletteSignature != 0 && it.signature == frame.paletteSignature }
    return when (frame.format) {
        MirrorFormat.RGB565 -> rgb565Pixels(frame)
        MirrorFormat.MONO_VLSB -> monoPixels(frame, active)
    }
}

/** Expands RGB565 to opaque ARGB by bit replication, the canonical expansion (plain scaling would floor). */
@Suppress("MagicNumber")
fun rgb565ToArgb(value: Int): Int {
    val r5 = (value ushr 11) and 0x1F
    val g6 = (value ushr 5) and 0x3F
    val b5 = value and 0x1F
    val r = (r5 shl 3) or (r5 ushr 2)
    val g = (g6 shl 2) or (g6 ushr 4)
    val b = (b5 shl 3) or (b5 ushr 2)
    return OPAQUE or (r shl 16) or (g shl 8) or b
}

@Suppress("MagicNumber")
private fun rgb565Pixels(frame: MirrorFrame): IntArray {
    val out = IntArray(frame.width * frame.height)
    val bytes = frame.pixels
    for (i in out.indices) {
        val at = i * 2
        if (at + 1 >= bytes.size) break
        out[i] = rgb565ToArgb((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8))
    }
    return out
}

/** One palette region with its colors already expanded; later regions win where they overlap, as in firmware. */
private class Region(val left: Int, val top: Int, val right: Int, val bottom: Int, val on: Int, val off: Int)

private fun monoPixels(frame: MirrorFrame, palette: MirrorPalette?): IntArray {
    val defaultOn = palette?.let { rgb565ToArgb(it.defaultOnColor) } ?: WHITE
    val defaultOff = palette?.let { rgb565ToArgb(it.defaultOffColor) } ?: BLACK
    val regions =
        palette?.regions.orEmpty().map {
            Region(it.x, it.y, it.x + it.width, it.y + it.height, rgb565ToArgb(it.on_color), rgb565ToArgb(it.off_color))
        }
    val out = IntArray(frame.width * frame.height)
    val rowRegions = ArrayList<Region>(regions.size)
    for (y in 0 until frame.height) {
        rowRegions.clear()
        regions.filterTo(rowRegions) { y >= it.top && y < it.bottom }
        // MONO_VLSB: byte x + (y / 8) * width holds 8 vertically stacked pixels, bit y % 8.
        val page = (y / PIXELS_PER_PAGE) * frame.width
        val bit = 1 shl (y % PIXELS_PER_PAGE)
        for (x in 0 until frame.width) {
            val set = (frame.pixels.getOrNull(page + x)?.toInt() ?: 0) and bit != 0
            val region = rowRegions.lastOrNull { x >= it.left && x < it.right }
            out[y * frame.width + x] =
                when {
                    region != null -> if (set) region.on else region.off
                    set -> defaultOn
                    else -> defaultOff
                }
        }
    }
    return out
}

private const val PIXELS_PER_PAGE = 8
private const val OPAQUE = -0x1000000
private const val WHITE = -0x1
private const val BLACK = OPAQUE

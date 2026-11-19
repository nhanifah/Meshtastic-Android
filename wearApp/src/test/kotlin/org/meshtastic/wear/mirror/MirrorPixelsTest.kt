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
import org.meshtastic.proto.DisplayPalette
import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorPixelsTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun `mono frames read vertical LSB-first pages`() {
        // 2x16: column 0 has pixel y=0 set (page 0, bit 0); column 1 has y=9 set (page 1, bit 1).
        val pixels = byteArrayOf(0b0000_0001, 0, 0, 0b0000_0010)
        val argb = mirrorArgb(MirrorFrame(width = 2, height = 16, frameId = 1, paletteSignature = 0, pixels), null)

        assertEquals(white, argb[0 * 2 + 0])
        assertEquals(black, argb[0 * 2 + 1])
        assertEquals(white, argb[9 * 2 + 1])
        assertEquals(black, argb[9 * 2 + 0])
    }

    @Test
    fun `rgb565 expands by bit replication`() {
        assertEquals(white, rgb565ToArgb(0xFFFF))
        assertEquals(black, rgb565ToArgb(0))
        assertEquals(red, rgb565ToArgb(0xF800))
    }

    @Test
    fun `rgb565 frames are little endian`() {
        val frame =
            MirrorFrame(
                width = 1,
                height = 1,
                frameId = 1,
                paletteSignature = 0,
                pixels = byteArrayOf(0x00, 0xF8.toByte()),
                format = MirrorFormat.RGB565,
            )
        assertEquals(red, mirrorArgb(frame, null).single())
    }

    @Test
    fun `a palette colors its region only when the frame references it`() {
        val region =
            DisplayPalette.ColorRegion.Builder()
                .also {
                    it.x = 0
                    it.y = 0
                    it.width = 1
                    it.height = 8
                    it.on_color = 0xF800
                    it.off_color = 0
                }
                .build()
        val palette =
            MirrorPalette(signature = 7, defaultOnColor = 0xFFFF, defaultOffColor = 0, regions = listOf(region))
        val pixels = byteArrayOf(0b1, 0b1)

        val colored = mirrorArgb(MirrorFrame(2, 8, 1, paletteSignature = 7, pixels = pixels), palette)
        assertEquals(red, colored[0], "inside the region")
        assertEquals(white, colored[1], "outside the region takes the default on color")

        val stale = mirrorArgb(MirrorFrame(2, 8, 1, paletteSignature = 3, pixels = pixels), palette)
        assertEquals(white, stale[0], "a palette for another signature is ignored")
    }
}

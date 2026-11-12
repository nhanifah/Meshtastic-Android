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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TarWebClientAssetsTest {

    /** Builds a minimal ustar archive: one header block per entry, data padded to 512 bytes, two zero blocks. */
    private fun tar(vararg entries: Triple<String, Char, ByteArray>): ByteArray {
        val out = mutableListOf<Byte>()
        entries.forEach { (name, type, data) ->
            val header = ByteArray(512)
            name.encodeToByteArray().copyInto(header, 0)
            data.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(header, 124)
            header[156] = type.code.toByte()
            "ustar".encodeToByteArray().copyInto(header, 257)
            out += header.toList()
            out += data.toList()
            repeat((512 - data.size % 512) % 512) { out += 0 }
        }
        repeat(1024) { out += 0 }
        return out.toByteArray()
    }

    @Test
    fun `gzipped files are served under their plain names`() {
        val body = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
        val assets = TarWebClientAssets.parse(tar(Triple("./index.html.gz", '0', body)))

        val asset = assets.open("index.html")!!
        assertContentEquals(body, asset.bytes)
        assertEquals("gzip", asset.contentEncoding)
        assertEquals("text/html; charset=utf-8", asset.contentType)
        assertNull(assets.open("index.html.gz"))
    }

    @Test
    fun `plain files keep their name and no encoding`() {
        val assets = TarWebClientAssets.parse(tar(Triple("./robots.txt", '0', "x".encodeToByteArray())))

        assertNull(assets.open("robots.txt")!!.contentEncoding)
    }

    @Test
    fun `directories and traversal entries are skipped`() {
        val assets =
            TarWebClientAssets.parse(
                tar(
                    Triple("./devices/", '5', ByteArray(0)),
                    Triple("../evil.js", '0', "x".encodeToByteArray()),
                    Triple("./devices/a.svg.gz", '0', "y".encodeToByteArray()),
                ),
            )

        assertEquals(1, assets.size)
        assertEquals("image/svg+xml", assets.open("devices/a.svg")!!.contentType)
    }

    @Test
    fun `a truncated archive is rejected`() {
        val whole = tar(Triple("./a.js", '0', ByteArray(2000)))

        assertFailsWith<IllegalArgumentException> { TarWebClientAssets.parse(whole.copyOfRange(0, 1024)) }
    }
}

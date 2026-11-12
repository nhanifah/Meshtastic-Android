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

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the archive `scripts/fetch-web-client.sh` stores: it must parse and contain a working entry point. */
class BundledWebClientTest {
    private val archive = File("../resources/src/commonMain/composeResources/files/webclient/build.tar")

    @Test
    fun `bundled archive parses and has the web client entry point`() {
        assertTrue(archive.isFile, "Run scripts/fetch-web-client.sh to bundle the web client")
        val assets = TarWebClientAssets.parse(archive.readBytes())

        val index = assets.open("index.html")!!
        assertEquals("gzip", index.contentEncoding)
        val html = GZIPInputStream(index.bytes.inputStream()).readBytes().decodeToString()
        assertContains(html, "<script type=\"module\"")
        assertTrue(assets.size > 100, "Expected the full client, found ${assets.size} files")
    }
}

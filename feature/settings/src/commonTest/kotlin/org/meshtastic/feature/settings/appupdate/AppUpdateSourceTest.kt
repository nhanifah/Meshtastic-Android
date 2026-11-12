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
package org.meshtastic.feature.settings.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateSourceTest {

    @Test
    fun keepsOnlyForkTagsAndSkipsDrafts() {
        val releases = AppUpdateSource.parseReleases(JSON)
        assertEquals(listOf(29323100, 29323050), releases.map { it.versionCode })
        val newest = releases.maxBy { it.versionCode }
        assertEquals("Range Test v3", newest.title)
        assertEquals(
            listOf("Meshtastic-Desktop-2.8.9000.exe", "meshtastic-fork-v29323100-google-universal.apk"),
            newest.assets.map { it.name },
        )
        assertEquals(173_752_832L, newest.assets.first().sizeBytes)
    }

    @Test
    fun malformedJsonGivesNoReleases() {
        assertTrue(AppUpdateSource.parseReleases("""{"message":"API rate limit exceeded"}""").isEmpty())
    }

    private companion object {
        val JSON =
            """
            [
              {"tag_name": "fork-v29323100", "name": "Range Test v3", "draft": false, "prerelease": true,
               "html_url": "https://example.test/r/3", "published_at": "2026-11-13T01:00:00Z", "body": "notes",
               "assets": [
                 {"name": "Meshtastic-Desktop-2.8.9000.exe", "size": 173752832,
                  "browser_download_url": "https://example.test/a.exe"},
                 {"name": "meshtastic-fork-v29323100-google-universal.apk", "size": 82389663,
                  "browser_download_url": "https://example.test/a.apk"}
               ]},
              {"tag_name": "fork-v29323200", "name": "draft", "draft": true, "assets": []},
              {"tag_name": "rangetest-v2-20261112", "name": "old naming", "draft": false, "assets": []},
              {"tag_name": "fork-v29323050", "name": "", "draft": false, "body": null, "assets": []}
            ]
            """
                .trimIndent()
    }
}

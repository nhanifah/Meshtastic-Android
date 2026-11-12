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

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.koin.core.annotation.Single

/** Where this build looks for its own updates. */
object AppUpdateConfig {
    /** The GitHub repository whose releases carry this fork's builds. */
    const val REPOSITORY = "nhanifah/Meshtastic-Android"

    /** Release tags name the build's version code: `fork-v29323000`. Other releases are ignored. */
    const val TAG_PREFIX = "fork-v"

    const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
}

/** One downloadable file of a release. */
data class AppUpdateAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

/** A published build of this fork, identified by the version code in its tag. */
data class AppUpdateRelease(
    val versionCode: Int,
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String,
    val assets: List<AppUpdateAsset>,
)

/**
 * Reads this fork's GitHub releases. Prereleases count (test builds are published that way); drafts and releases whose
 * tag does not follow [AppUpdateConfig.TAG_PREFIX] are skipped.
 */
@Single
class AppUpdateSource(private val httpClient: HttpClient) {

    /** The newest release, or null when there is none. Network or parse failures throw. */
    suspend fun latest(): AppUpdateRelease? {
        val response =
            httpClient.get(AppUpdateConfig.RELEASES_API) { header(HttpHeaders.Accept, "application/vnd.github+json") }
        check(response.status.isSuccess()) { "GitHub answered ${response.status.value}" }
        return parseReleases(response.bodyAsText()).maxByOrNull { it.versionCode }
    }

    companion object {
        private val TAG_REGEX = Regex("""^${AppUpdateConfig.TAG_PREFIX}(\d+)$""")

        /** Parses the GitHub "list releases" JSON into the releases this updater understands. */
        fun parseReleases(json: String): List<AppUpdateRelease> =
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.toRelease()
            }

        @Suppress("ReturnCount")
        private fun JsonObject.toRelease(): AppUpdateRelease? {
            if (bool("draft")) return null
            val tag = string("tag_name") ?: return null
            val code = TAG_REGEX.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return AppUpdateRelease(
                versionCode = code,
                tag = tag,
                title = string("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = string("body").orEmpty(),
                pageUrl = string("html_url") ?: AppUpdateConfig.RELEASES_PAGE,
                publishedAt = string("published_at").orEmpty(),
                assets =
                this["assets"]?.jsonArray.orEmpty().mapNotNull { asset ->
                    val obj = asset.jsonObject
                    val name = obj.string("name") ?: return@mapNotNull null
                    val url = obj.string("browser_download_url") ?: return@mapNotNull null
                    AppUpdateAsset(name, url, obj["size"]?.jsonPrimitive?.longOrNull ?: 0L)
                },
            )
        }

        private fun JsonObject.string(key: String): String? =
            this[key]?.let { runCatching { it.jsonPrimitive }.getOrNull() }?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean =
            this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
    }
}

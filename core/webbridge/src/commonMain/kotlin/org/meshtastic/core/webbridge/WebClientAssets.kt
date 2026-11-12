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

/**
 * One static file of the bundled web client. [contentEncoding] is set when [bytes] are already compressed (for example
 * `gzip`) and must be sent as they are.
 */
class WebAsset(val bytes: ByteArray, val contentType: String, val contentEncoding: String? = null)

/** Serves the bundled web client's static files. The platform supplies it (Android reads them from APK assets). */
fun interface WebClientAssets {
    /** Returns the file at [path] (no leading slash, already normalised), or null when there is none. */
    fun open(path: String): WebAsset?

    companion object {
        /** No bundled client: the bridge still pairs and serves the API, and `/` explains what is missing. */
        val NONE = WebClientAssets { null }

        /** Content type by file extension, for the handful of types a static web build contains. */
        fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"

            "js",
            "mjs",
            -> "text/javascript; charset=utf-8"

            "css" -> "text/css; charset=utf-8"

            "json",
            "webmanifest",
            -> "application/json"

            "svg" -> "image/svg+xml"

            "png" -> "image/png"

            "ico" -> "image/x-icon"

            "woff2" -> "font/woff2"

            "wasm" -> "application/wasm"

            else -> "application/octet-stream"
        }
    }
}

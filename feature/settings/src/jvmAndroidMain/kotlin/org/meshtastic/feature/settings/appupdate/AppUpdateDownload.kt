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
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.meshtastic.core.common.util.ioDispatcher
import java.io.File
import java.io.IOException

private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_STEP = 0.01f

/**
 * Streams [asset] into [directory] without holding it in memory (installers are 80–180 MB), with no overall time limit
 * and no HTTP caching. The file is written under a temporary name and renamed only once complete, so a half-finished
 * download is never offered for install.
 */
internal suspend fun downloadAsset(
    httpClient: HttpClient,
    asset: AppUpdateAsset,
    directory: File,
    onProgress: (Float?) -> Unit,
): File = withContext(ioDispatcher) {
    directory.mkdirs()
    // Only the file name part of the asset name, so a crafted name cannot escape the directory.
    val target = File(directory, File(asset.name).name)
    val partial = File(directory, target.name + ".part")
    // One update at a time: clear out earlier downloads so they do not pile up.
    directory.listFiles()?.forEach { it.delete() }
    httpClient
        .prepareGet(asset.downloadUrl) {
            header(HttpHeaders.CacheControl, "no-store")
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }
        .execute { response ->
            if (!response.status.isSuccess()) throw IOException("Download failed: HTTP ${response.status.value}")
            val total = response.contentLength()?.takeIf { it > 0 } ?: asset.sizeBytes.takeIf { it > 0 }
            var written = 0L
            var reported = -1f
            response.bodyAsChannel().toInputStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        written += read
                        val progress = total?.let { written.toFloat() / it }
                        if (progress == null || progress - reported >= PROGRESS_STEP) {
                            reported = progress ?: reported
                            onProgress(progress)
                        }
                    }
                }
            }
            if (total != null && written != total) {
                throw IOException("Incomplete download: $written of $total bytes")
            }
        }
    if (!partial.renameTo(target)) throw IOException("Could not finish writing ${target.name}")
    onProgress(1f)
    target
}

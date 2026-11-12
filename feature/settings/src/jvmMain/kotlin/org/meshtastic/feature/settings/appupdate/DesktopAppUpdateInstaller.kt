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

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import org.koin.core.annotation.Single
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/**
 * Desktop half of the updater. On Windows it downloads the `.exe` installer (or `.msi`), starts it and quits so the
 * installer can replace the app's files; the installer upgrades the existing install in place. On macOS and Linux it
 * opens the release page instead.
 */
@Single(binds = [AppUpdateInstaller::class])
class DesktopAppUpdateInstaller(private val httpClient: HttpClient) : AppUpdateInstaller {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    override fun pickAsset(release: AppUpdateRelease): AppUpdateAsset? {
        if (!isWindows) return null
        return release.assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
    }

    override suspend fun download(asset: AppUpdateAsset, onProgress: (Float?) -> Unit): DownloadedUpdate {
        val dir = File(System.getProperty("java.io.tmpdir"), DOWNLOAD_DIR)
        val file = downloadAsset(httpClient, asset, dir, onProgress)
        return DownloadedUpdate(file.absolutePath, asset)
    }

    override suspend fun install(update: DownloadedUpdate): AppUpdateInstallResult {
        if (!isWindows) {
            openPage(AppUpdateConfig.RELEASES_PAGE)
            return AppUpdateInstallResult.OPENED_IN_BROWSER
        }
        val command =
            if (update.path.endsWith(".msi", ignoreCase = true)) {
                listOf("msiexec", "/i", update.path)
            } else {
                listOf(update.path)
            }
        ProcessBuilder(command).inheritIO().start()
        Logger.i { "Update installer started; quitting so it can replace the app" }
        // Give the installer a moment to come up before the files it replaces are released.
        Thread {
            Thread.sleep(QUIT_DELAY_MS)
            exitProcess(0)
        }
            .apply { isDaemon = true }
            .start()
        return AppUpdateInstallResult.STARTED
    }

    override fun openPage(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { Logger.w(it) { "Could not open the browser for $url" } }
    }

    private companion object {
        const val DOWNLOAD_DIR = "meshtastic-update"
        const val QUIT_DELAY_MS = 1_500L
    }
}

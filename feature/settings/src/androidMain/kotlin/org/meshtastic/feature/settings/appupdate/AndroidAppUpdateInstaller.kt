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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import org.koin.core.annotation.Single
import java.io.File

/**
 * Android half of the updater: picks the universal APK of this build's flavor, downloads it to the cache and opens the
 * system package installer on it through a FileProvider (authority `<package>.appupdate`, declared by the app).
 */
@Single(binds = [AppUpdateInstaller::class])
class AndroidAppUpdateInstaller(private val context: Context, private val httpClient: HttpClient) : AppUpdateInstaller {

    override fun pickAsset(release: AppUpdateRelease): AppUpdateAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val flavor = if (isGoogleFlavor()) "google" else "fdroid"
        return apks.firstOrNull { it.name.contains(flavor, ignoreCase = true) && it.isUniversal() }
            ?: apks.firstOrNull { it.name.contains(flavor, ignoreCase = true) }
    }

    override suspend fun download(asset: AppUpdateAsset, onProgress: (Float?) -> Unit): DownloadedUpdate {
        val file = downloadAsset(httpClient, asset, File(context.cacheDir, CACHE_DIR), onProgress)
        return DownloadedUpdate(file.absolutePath, asset)
    }

    override fun install(update: DownloadedUpdate): AppUpdateInstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return AppUpdateInstallResult.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", File(update.path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return AppUpdateInstallResult.STARTED
    }

    override fun openPage(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Only the google flavor's manifest declares a Google Maps key, so its presence names the flavor. */
    private fun isGoogleFlavor(): Boolean = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.containsKey(MAPS_KEY_META) == true
    }
        .getOrDefault(false)

    private fun AppUpdateAsset.isUniversal() = name.contains("universal", ignoreCase = true)

    private companion object {
        const val CACHE_DIR = "app_update"
        const val AUTHORITY_SUFFIX = ".appupdate"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        const val MAPS_KEY_META = "com.google.android.geo.API_KEY"
    }
}

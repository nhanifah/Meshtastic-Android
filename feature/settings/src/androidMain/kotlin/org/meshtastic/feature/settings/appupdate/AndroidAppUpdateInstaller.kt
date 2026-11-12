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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.ioDispatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android half of the updater: picks the universal APK of this build's flavor, downloads it to the cache and installs
 * it through a [PackageInstaller] session.
 *
 * The session streams the APK to the system itself. Handing the file to an installer activity by URI instead
 * (`ACTION_VIEW` on a FileProvider URI) fails on some ROMs, notably Xiaomi HyperOS/MIUI, whose installer forwards the
 * intent without the read grant, so the system reports a broken file ("There was a problem parsing the package").
 */
@Single(binds = [AppUpdateInstaller::class])
class AndroidAppUpdateInstaller(private val context: Context, private val httpClient: HttpClient) : AppUpdateInstaller {

    private val statusReceiverRegistered = AtomicBoolean(false)

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

    override suspend fun install(update: DownloadedUpdate): AppUpdateInstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return AppUpdateInstallResult.NEEDS_PERMISSION
        }
        registerStatusReceiver()
        withContext(ioDispatcher) { commitSession(File(update.path)) }
        return AppUpdateInstallResult.STARTED
    }

    override fun openPage(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies [apk] into a fresh install session and commits it; the result arrives at [StatusReceiver]. */
    private fun commitSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Drop sessions left over from an earlier attempt so they do not hold space.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SESSION_APK_NAME, 0, apk.length()).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                        session.fsync(output)
                    }
                }
                val callback = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun registerStatusReceiver() {
        if (!statusReceiverRegistered.compareAndSet(false, true)) return
        ContextCompat.registerReceiver(
            context.applicationContext,
            StatusReceiver(),
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Shows the system confirmation when the session needs it, and reports a failed install. */
    private class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                PackageInstaller.STATUS_SUCCESS,
                PackageInstaller.STATUS_FAILURE_ABORTED,
                -> Unit

                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Logger.w { "Update install failed: status $status, $message" }
                    Toast.makeText(context, message ?: "Install failed ($status)", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        const val SESSION_APK_NAME = "base.apk"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val ACTION_INSTALL_STATUS = "org.meshtastic.feature.settings.appupdate.INSTALL_STATUS"

        const val MAPS_KEY_META = "com.google.android.geo.API_KEY"
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.nowMillis

/** A file the platform has downloaded and can hand to its installer. */
data class DownloadedUpdate(val path: String, val asset: AppUpdateAsset)

/** What happened when the installer was asked to install. */
enum class AppUpdateInstallResult {
    /** The system installer is open; it takes over from here. */
    STARTED,

    /** Android: the user must first allow this app to install apps; the settings page was opened. */
    NEEDS_PERMISSION,

    /** No in-app install on this platform; the release page was opened in the browser instead. */
    OPENED_IN_BROWSER,
}

/** Platform half of the updater: which file fits this device, how to fetch it, and how to start installing it. */
interface AppUpdateInstaller {
    /** The asset of [release] this platform and build flavor can install, or null if none fits. */
    fun pickAsset(release: AppUpdateRelease): AppUpdateAsset?

    /** Downloads [asset], reporting 0–1 progress (or null when the size is unknown). */
    suspend fun download(asset: AppUpdateAsset, onProgress: (Float?) -> Unit): DownloadedUpdate

    /** Hands [update] to the system installer. On desktop this may quit the app so files can be replaced. */
    suspend fun install(update: DownloadedUpdate): AppUpdateInstallResult

    /** Opens [url] (the release page) in the browser. */
    fun openPage(url: String)
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    data object UpToDate : AppUpdateState

    /** A newer build exists; [asset] is null when it has no file for this platform. */
    data class Available(val release: AppUpdateRelease, val asset: AppUpdateAsset?) : AppUpdateState

    data class Downloading(val release: AppUpdateRelease, val progress: Float?) : AppUpdateState

    data class ReadyToInstall(val release: AppUpdateRelease, val update: DownloadedUpdate) : AppUpdateState

    data class Failed(val reason: String, val release: AppUpdateRelease? = null) : AppUpdateState
}

/**
 * Checks this fork's GitHub releases for a build with a higher version code than the running one, downloads it and
 * starts the platform installer. Application-scoped so a download keeps going when the screen is left.
 */
@Single
class AppUpdateManager(
    private val source: AppUpdateSource,
    private val installer: AppUpdateInstaller,
    private val buildConfig: BuildConfigProvider,
    private val applicationScope: ApplicationCoroutineScope,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var lastCheckAt = 0L
    private val stateFlow = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = stateFlow.asStateFlow()

    val currentVersionName: String
        get() = buildConfig.versionName

    val currentVersionCode: Int
        get() = buildConfig.versionCode

    /** Checks unless a check finished within [AUTO_CHECK_INTERVAL_MS]; for the passive check on app start. */
    fun checkIfStale() {
        if (nowMillis - lastCheckAt >= AUTO_CHECK_INTERVAL_MS) check()
    }

    fun check() = launchExclusive {
        stateFlow.value = AppUpdateState.Checking
        val release = source.latest()
        lastCheckAt = nowMillis
        stateFlow.value =
            if (release != null && release.versionCode > buildConfig.versionCode) {
                AppUpdateState.Available(release, installer.pickAsset(release))
            } else {
                AppUpdateState.UpToDate
            }
    }

    fun download() {
        val available = stateFlow.value as? AppUpdateState.Available ?: return
        val asset = available.asset ?: return
        launchExclusive(available.release) {
            stateFlow.value = AppUpdateState.Downloading(available.release, progress = 0f)
            val update =
                installer.download(asset) { progress ->
                    stateFlow.value = AppUpdateState.Downloading(available.release, progress)
                }
            stateFlow.value = AppUpdateState.ReadyToInstall(available.release, update)
        }
    }

    /** Starts the installer; the state stays ready so the user can try again after granting permission. */
    suspend fun install(): AppUpdateInstallResult? {
        val ready = stateFlow.value as? AppUpdateState.ReadyToInstall ?: return null
        return runCatching { installer.install(ready.update) }
            .onFailure { e ->
                Logger.w(e) { "Could not start the update installer" }
                stateFlow.value = AppUpdateState.Failed(e.message ?: e::class.simpleName.orEmpty(), ready.release)
            }
            .getOrNull()
    }

    fun openReleasePage(release: AppUpdateRelease?) =
        installer.openPage(release?.pageUrl ?: AppUpdateConfig.RELEASES_PAGE)

    private fun launchExclusive(release: AppUpdateRelease? = null, block: suspend () -> Unit) {
        applicationScope.launch {
            mutex.withLock {
                if (job?.isActive == true) return@withLock
                job = applicationScope.launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Logger.w(e) { "App update step failed" }
                        stateFlow.value = AppUpdateState.Failed(e.message ?: e::class.simpleName.orEmpty(), release)
                    }
                }
            }
        }
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

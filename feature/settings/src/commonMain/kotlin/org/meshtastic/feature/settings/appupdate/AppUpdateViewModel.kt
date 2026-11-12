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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.KoinViewModel

/** Backs the App update screen; the work itself lives in the application-scoped [AppUpdateManager]. */
@KoinViewModel
class AppUpdateViewModel(private val manager: AppUpdateManager) : ViewModel() {

    val state: StateFlow<AppUpdateState> = manager.state

    val currentVersionName: String = manager.currentVersionName

    val currentVersionCode: Int = manager.currentVersionCode

    private val lastInstallFlow = MutableStateFlow<AppUpdateInstallResult?>(null)

    /** The outcome of the last install attempt, for the hint under the button. */
    val lastInstall: StateFlow<AppUpdateInstallResult?> = lastInstallFlow.asStateFlow()

    init {
        if (manager.state.value == AppUpdateState.Idle) manager.check()
    }

    fun check() = manager.check()

    fun download() = manager.download()

    fun install() {
        lastInstallFlow.value = manager.install()
    }

    fun openReleasePage(release: AppUpdateRelease?) = manager.openReleasePage(release)
}

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
@file:Suppress("TooManyFunctions")

package org.meshtastic.feature.settings.appupdate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.app_update_all_releases
import org.meshtastic.core.resources.app_update_available
import org.meshtastic.core.resources.app_update_build
import org.meshtastic.core.resources.app_update_check_again
import org.meshtastic.core.resources.app_update_checking
import org.meshtastic.core.resources.app_update_current
import org.meshtastic.core.resources.app_update_download_install
import org.meshtastic.core.resources.app_update_downloading
import org.meshtastic.core.resources.app_update_failed
import org.meshtastic.core.resources.app_update_install
import org.meshtastic.core.resources.app_update_installer_started
import org.meshtastic.core.resources.app_update_needs_permission
import org.meshtastic.core.resources.app_update_no_asset
import org.meshtastic.core.resources.app_update_notes
import org.meshtastic.core.resources.app_update_open_release
import org.meshtastic.core.resources.app_update_title
import org.meshtastic.core.resources.app_update_try_again
import org.meshtastic.core.resources.app_update_up_to_date
import org.meshtastic.core.resources.back
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.SystemUpdate

private val CONTENT_MAX_WIDTH = 640.dp
private const val PERCENT = 100
private const val BYTES_PER_MB = 1024f * 1024f
private const val NOTES_MAX_CHARS = 4_000

/** Shows the installed build, checks this fork's GitHub releases, and downloads and installs a newer build. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateScreen(viewModel: AppUpdateViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastInstall by viewModel.lastInstall.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_update_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        Res.string.app_update_current,
                        viewModel.currentVersionName,
                        viewModel.currentVersionCode,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateCard(
                    state = state,
                    lastInstall = lastInstall,
                    actions =
                    UpdateActions(
                        check = viewModel::check,
                        download = viewModel::download,
                        install = viewModel::install,
                        openRelease = viewModel::openReleasePage,
                    ),
                )
                TextButton(onClick = { viewModel.openReleasePage(null) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.app_update_all_releases))
                }
            }
        }
    }
}

private class UpdateActions(
    val check: () -> Unit,
    val download: () -> Unit,
    val install: () -> Unit,
    val openRelease: (AppUpdateRelease?) -> Unit,
)

@Composable
private fun UpdateCard(state: AppUpdateState, lastInstall: AppUpdateInstallResult?, actions: UpdateActions) {
    val highlighted = state is AppUpdateState.Available || state is AppUpdateState.ReadyToInstall
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor =
            if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                AppUpdateState.Idle,
                AppUpdateState.Checking,
                -> Busy(stringResource(Res.string.app_update_checking))

                AppUpdateState.UpToDate -> UpToDate(actions.check)

                is AppUpdateState.Available -> Available(state, actions)

                is AppUpdateState.Downloading -> Downloading(state)

                is AppUpdateState.ReadyToInstall -> ReadyToInstall(state, lastInstall, actions.install)

                is AppUpdateState.Failed -> Failed(state.reason, actions.check)
            }
        }
    }
}

@Composable
private fun Failed(reason: String, onRetry: () -> Unit) = Section {
    Text(stringResource(Res.string.app_update_failed, reason), color = MaterialTheme.colorScheme.error)
    PrimaryButton(stringResource(Res.string.app_update_try_again), onRetry)
}

/** One state's content, stacked with even spacing. */
@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun UpToDate(onCheck: () -> Unit) = Section {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(MeshtasticIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.app_update_up_to_date), Modifier.padding(start = 12.dp))
    }
    OutlinedButton(onClick = onCheck, modifier = Modifier.align(Alignment.End)) {
        Text(stringResource(Res.string.app_update_check_again))
    }
}

@Composable
private fun Available(state: AppUpdateState.Available, actions: UpdateActions) = Section {
    ReleaseHeader(state.release)
    val asset = state.asset
    if (asset == null) {
        Text(stringResource(Res.string.app_update_no_asset))
        PrimaryButton(stringResource(Res.string.app_update_open_release)) { actions.openRelease(state.release) }
    } else {
        val size = "${(asset.sizeBytes / BYTES_PER_MB).toInt()} MB"
        PrimaryButton(stringResource(Res.string.app_update_download_install, size), actions.download)
    }
    ReleaseNotes(state.release)
}

@Composable
private fun Downloading(state: AppUpdateState.Downloading) = Section {
    ReleaseHeader(state.release)
    val progress = state.progress
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    Text(
        stringResource(Res.string.app_update_downloading, progress?.let { "${(it * PERCENT).toInt()}%" }.orEmpty()),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ReadyToInstall(
    state: AppUpdateState.ReadyToInstall,
    lastInstall: AppUpdateInstallResult?,
    onInstall: () -> Unit,
) = Section {
    ReleaseHeader(state.release)
    PrimaryButton(stringResource(Res.string.app_update_install), onInstall)
    when (lastInstall) {
        AppUpdateInstallResult.NEEDS_PERMISSION -> Hint(stringResource(Res.string.app_update_needs_permission))

        AppUpdateInstallResult.STARTED -> Hint(stringResource(Res.string.app_update_installer_started))

        AppUpdateInstallResult.OPENED_IN_BROWSER,
        null,
        -> Unit
    }
}

@Composable
private fun ReleaseHeader(release: AppUpdateRelease) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(MeshtasticIcons.SystemUpdate, contentDescription = null, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(Res.string.app_update_available), style = MaterialTheme.typography.titleMedium)
            Text(release.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(Res.string.app_update_build, release.versionCode) +
                    release.publishedAt.take(DATE_LENGTH).let { if (it.isEmpty()) "" else " · $it" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReleaseNotes(release: AppUpdateRelease) {
    if (release.notes.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.app_update_notes), style = MaterialTheme.typography.titleSmall)
        Text(release.notes.take(NOTES_MAX_CHARS).trim(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** `2026-11-12` out of GitHub's ISO-8601 timestamp. */
private const val DATE_LENGTH = 10

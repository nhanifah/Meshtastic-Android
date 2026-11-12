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
package org.meshtastic.feature.settings.webaccess

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.back
import org.meshtastic.core.resources.disconnect
import org.meshtastic.core.resources.web_access
import org.meshtastic.core.resources.web_access_browser
import org.meshtastic.core.resources.web_access_browsers
import org.meshtastic.core.resources.web_access_certificate_notice
import org.meshtastic.core.resources.web_access_connect_hint
import org.meshtastic.core.resources.web_access_enabled
import org.meshtastic.core.resources.web_access_enabled_desc
import org.meshtastic.core.resources.web_access_fingerprint
import org.meshtastic.core.resources.web_access_new_code
import org.meshtastic.core.resources.web_access_no_browsers
import org.meshtastic.core.resources.web_access_no_client
import org.meshtastic.core.resources.web_access_no_network
import org.meshtastic.core.resources.web_access_scan
import org.meshtastic.core.resources.web_access_start_failed
import org.meshtastic.core.resources.web_access_starting
import org.meshtastic.core.ui.component.SwitchPreference
import org.meshtastic.core.ui.component.TitledCard
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.util.rememberQrCodePainter
import org.meshtastic.core.webbridge.WebBridgeManager
import org.meshtastic.core.webbridge.WebBridgeSessionInfo
import org.meshtastic.core.webbridge.WebBridgeState

private const val QR_SIZE_DP = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAccessScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val manager: WebBridgeManager = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.web_access)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MeshtasticIcons.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        WebAccessContent(
            state = state,
            onEnabledChange = { enabled -> if (enabled) manager.start() else manager.stop() },
            onNewCode = manager::refreshPairingCode,
            onRevoke = manager::revoke,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Stateless body of [WebAccessScreen], previewable without DI. */
@Composable
internal fun WebAccessContent(
    state: WebBridgeState,
    onEnabledChange: (Boolean) -> Unit,
    onNewCode: () -> Unit,
    onRevoke: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitledCard(title = null) {
            SwitchPreference(
                title = stringResource(Res.string.web_access_enabled),
                summary = stringResource(Res.string.web_access_enabled_desc),
                checked = state is WebBridgeState.Running || state is WebBridgeState.Starting,
                enabled = state !is WebBridgeState.Starting,
                loading = state is WebBridgeState.Starting,
                onCheckedChange = onEnabledChange,
            )
        }
        when (state) {
            WebBridgeState.Off -> Unit

            WebBridgeState.Starting -> StatusLine(stringResource(Res.string.web_access_starting), showProgress = true)

            is WebBridgeState.Failed ->
                StatusLine(
                    text =
                    stringResource(
                        when (state.reason) {
                            WebBridgeState.Failed.Reason.NO_NETWORK -> Res.string.web_access_no_network
                            WebBridgeState.Failed.Reason.START_FAILED -> Res.string.web_access_start_failed
                        },
                    ),
                    isError = true,
                )

            is WebBridgeState.Running -> RunningSection(state, onNewCode, onRevoke)
        }
    }
}

@Composable
private fun RunningSection(state: WebBridgeState.Running, onNewCode: () -> Unit, onRevoke: (String) -> Unit) {
    val origin = state.endpoint.origin
    TitledCard(title = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.pairingUrl.isNotEmpty()) {
                val sizePx = with(LocalDensity.current) { QR_SIZE_DP.dp.roundToPx() }
                // QR codes need a light quiet zone to scan reliably, whatever the app theme.
                Box(modifier = Modifier.background(Color.White).padding(12.dp)) {
                    Image(
                        painter = rememberQrCodePainter(state.pairingUrl, sizePx),
                        contentDescription = null,
                        modifier = Modifier.size(QR_SIZE_DP.dp),
                    )
                }
            }
            Text(stringResource(Res.string.web_access_scan), style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(origin, style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = onNewCode) { Text(stringResource(Res.string.web_access_new_code)) }
            Text(
                text = stringResource(Res.string.web_access_connect_hint, origin),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.hasWebClient) StatusLine(stringResource(Res.string.web_access_no_client), isError = true)
        }
    }
    TitledCard(title = stringResource(Res.string.web_access_fingerprint)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(
                    text = state.endpoint.certificateSha256,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = stringResource(Res.string.web_access_certificate_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TitledCard(title = stringResource(Res.string.web_access_browsers)) {
        if (state.sessions.isEmpty()) {
            Text(
                text = stringResource(Res.string.web_access_no_browsers),
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                SessionRow(session, onRevoke)
            }
        }
    }
}

@Composable
private fun SessionRow(session: WebBridgeSessionInfo, onRevoke: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(Res.string.web_access_browser, session.id))
        TextButton(onClick = { onRevoke(session.id) }) { Text(stringResource(Res.string.disconnect)) }
    }
}

@Composable
private fun StatusLine(text: String, showProgress: Boolean = false, isError: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showProgress) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

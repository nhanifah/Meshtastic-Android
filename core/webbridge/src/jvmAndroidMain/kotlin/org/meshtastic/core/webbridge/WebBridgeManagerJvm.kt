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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioInterfaceService
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One running bridge and the pieces the manager needs to talk to it. */
private class RunningBridge(
    val server: WebBridgeServer,
    val sessions: WebBridgeSessions,
    val hub: WebBridgeHub,
    val jobs: List<Job>,
)

internal class WebBridgeManagerJvm(
    private val scope: CoroutineScope,
    private val radioInterfaceService: RadioInterfaceService,
    private val configDumpProvider: ConfigDumpProvider,
    private val dispatchers: CoroutineDispatchers,
    private val loadWebClientArchive: suspend () -> ByteArray?,
    private val findAddress: () -> InetAddress? = ::findLocalNetworkAddress,
) : WebBridgeManager {
    override val isSupported = true

    private val lifecycle = Mutex()
    private val publishLock = Any()

    @Volatile private var running: RunningBridge? = null

    private val _state = MutableStateFlow<WebBridgeState>(WebBridgeState.Off)
    override val state: StateFlow<WebBridgeState> = _state.asStateFlow()

    override fun start() {
        scope.launch(dispatchers.io) { lifecycle.withLock { if (running == null) startLocked() } }
    }

    override fun stop() {
        scope.launch(dispatchers.io) { lifecycle.withLock { stopLocked() } }
    }

    override fun refreshPairingCode() {
        val bridge = running ?: return
        publishPairingCode(bridge.sessions, keepPrevious = false)
    }

    override fun revoke(id: String) {
        val bridge = running ?: return
        bridge.sessions.revoke(id)
        bridge.hub.forget(id)
    }

    private suspend fun startLocked() {
        _state.value = WebBridgeState.Starting
        val address = findAddress()
        if (address == null) {
            _state.value = WebBridgeState.Failed(WebBridgeState.Failed.Reason.NO_NETWORK)
        } else {
            startOn(address)
        }
    }

    // CancellationException is caught first and rethrown; the generic catch below only turns real failures into Failed.
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    private suspend fun startOn(address: InetAddress) {
        val assets =
            loadWebClientArchive()
                ?.let { archive -> runCatching { TarWebClientAssets.parse(archive) }.getOrNull() }
                ?.takeIf { it.size > 0 }
        val sessions = WebBridgeSessions(clockMillis = System::currentTimeMillis)
        val hub =
            WebBridgeHub(
                radioFrames = radioInterfaceService.receivedDataMirror,
                radioWriter = radioInterfaceService,
                configDumpProvider = configDumpProvider,
                clockMillis = System::currentTimeMillis,
            )
        val server = WebBridgeServer(hub, sessions, assets ?: WebClientAssets.NONE, dispatchers.io)
        val endpoint =
            try {
                startOnPreferredPort(server, address)
            } catch (e: CancellationException) {
                server.stop()
                throw e
            } catch (e: Exception) {
                // Not only IOException and GeneralSecurityException: a KeyStore, BouncyCastle or Ktor failure must
                // also end in Failed, or the screen would sit on "Starting…" with a half-started server leaking.
                fail(server, e)
                return
            }

        _state.value =
            WebBridgeState.Running(
                endpoint = endpoint,
                pairingUrl = "",
                pairingCode = "",
                sessions = emptyList(),
                hasWebClient = assets != null,
            )
        val jobs =
            listOf(
                // Keep the code usable: re-issue it shortly before it expires. The previous code stays valid until
                // its own expiry, so someone halfway through typing it is not cut off.
                scope.launch {
                    publishPairingCode(sessions, keepPrevious = false)
                    while (isActive) {
                        delay(PAIRING_REFRESH_MILLIS)
                        publishPairingCode(sessions, keepPrevious = true)
                    }
                },
                scope.launch {
                    var knownIds = emptySet<String>()
                    sessions.sessions.collect { list ->
                        _state.update { if (it is WebBridgeState.Running) it.copy(sessions = list) else it }
                        // A successful pairing used up the code on screen; show a fresh one for the next
                        // browser. Compare ids, not counts: at the session limit a new pairing evicts the oldest
                        // and the count stays put.
                        val ids = list.mapTo(HashSet()) { it.id }
                        if (!knownIds.containsAll(ids)) publishPairingCode(sessions, keepPrevious = false)
                        knownIds = ids
                    }
                },
            )
        running = RunningBridge(server, sessions, hub, jobs)
        Logger.i { "Web access started on port ${endpoint.port}" }
    }

    /** The well-known port keeps the address stable across restarts; a clone or another app may hold it. */
    private suspend fun startOnPreferredPort(server: WebBridgeServer, address: InetAddress): WebBridgeEndpoint = try {
        server.start(address, PREFERRED_PORT, scope)
    } catch (_: BindException) {
        server.start(address, 0, scope)
    }

    private fun fail(server: WebBridgeServer, error: Exception) {
        Logger.w(error) { "Web access failed to start" }
        server.stop()
        _state.value = WebBridgeState.Failed(WebBridgeState.Failed.Reason.START_FAILED)
    }

    private fun stopLocked() {
        running?.let { bridge ->
            bridge.jobs.forEach { it.cancel() }
            bridge.server.stop()
        }
        running = null
        _state.value = WebBridgeState.Off
    }

    /** Issue and show as one step, so a rotation racing a pairing can never leave an invalidated code on screen. */
    private fun publishPairingCode(sessions: WebBridgeSessions, keepPrevious: Boolean) = synchronized(publishLock) {
        val code = sessions.issuePairingToken(keepPrevious)
        _state.update { current ->
            if (current is WebBridgeState.Running) {
                current.copy(
                    pairingUrl = "${current.endpoint.origin}/pair#t=$code",
                    pairingCode = WebBridgeSessions.formatCode(code),
                )
            } else {
                current
            }
        }
    }

    private companion object {
        const val PREFERRED_PORT = 8443
        const val PAIRING_REFRESH_MILLIS = WebBridgeSessions.DEFAULT_TOKEN_TTL_MILLIS - 60_000L
    }
}

/**
 * Picks the IPv4 address a laptop on the same network can reach: a private (site-local) address on an interface that is
 * up. Wi-Fi and hotspot interfaces win over wired ones. Mobile-data, VPN and Wi-Fi Direct interfaces are skipped by
 * name: carriers often hand out carrier-grade NAT addresses in 10.0.0.0/8, which count as site-local but which no
 * laptop on the Wi-Fi can reach.
 */
internal fun findLocalNetworkAddress(): InetAddress? {
    val candidates = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    }
        .getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .filterNot { nic -> UNREACHABLE_INTERFACE_PREFIXES.any(nic.name::startsWith) }
        .flatMap { nic -> nic.inetAddresses.toList().map { nic.name to it } }
        .filter { (_, address) -> address is Inet4Address && address.isSiteLocalAddress }
    return candidates.minByOrNull { (name, _) -> interfacePreference(name) }?.second
}

/** Interface-name prefixes, most preferred first: Wi-Fi, then hotspot, then wired. */
private val PREFERRED_INTERFACE_PREFIXES = listOf(listOf("wlan"), listOf("swlan", "ap", "softap"), listOf("en", "eth"))

/** Mobile data (`rmnet`, `ccmni` on MediaTek), VPN tunnels, Wi-Fi Direct and placeholder interfaces. */
private val UNREACHABLE_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "tun", "utun", "ppp", "p2p", "dummy")

private fun interfacePreference(name: String): Int =
    PREFERRED_INTERFACE_PREFIXES.indexOfFirst { prefixes -> prefixes.any(name::startsWith) }.takeIf { it >= 0 }
        ?: PREFERRED_INTERFACE_PREFIXES.size

actual fun createWebBridgeManager(
    scope: CoroutineScope,
    radioInterfaceService: RadioInterfaceService,
    configDumpProvider: ConfigDumpProvider,
    dispatchers: CoroutineDispatchers,
    loadWebClientArchive: suspend () -> ByteArray?,
): WebBridgeManager =
    WebBridgeManagerJvm(scope, radioInterfaceService, configDumpProvider, dispatchers, loadWebClientArchive)

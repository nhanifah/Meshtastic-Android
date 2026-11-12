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
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Serves the web bridge over HTTPS on one local network address.
 *
 * Ktor's CIO engine handles HTTP but cannot terminate TLS, and the engines that can (Netty, Jetty) add megabytes to the
 * APK. So CIO listens on loopback only, and a small TLS front end on [start]'s address decrypts each connection and
 * pipes it to CIO. Loopback is reachable by other apps on the phone, but every API call still needs a session cookie
 * that only a paired browser holds.
 */
class WebBridgeServer(
    private val hub: WebBridgeHub,
    private val sessions: WebBridgeSessions,
    private val assets: WebClientAssets,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var acceptJob: Job? = null
    private var http: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var tlsSocket: ServerSocket? = null

    /**
     * Starts listening on [address]:[port] (0 picks a free port) and returns where browsers can reach it. Connection
     * handling runs in [scope] until [stop]. Any earlier instance is stopped first.
     */
    suspend fun start(address: InetAddress, port: Int, scope: CoroutineScope): WebBridgeEndpoint {
        stop()
        val identity = WebBridgeCertificate.issue(listOfNotNull(address.hostAddress))

        val httpServer =
            embeddedServer(CIO, host = LOOPBACK, port = 0) { webBridgeModule(hub, sessions, assets) }.also { http = it }
        httpServer.startSuspend(wait = false)
        val loopbackPort = httpServer.engine.resolvedConnectors().first().port

        val tls = identity.sslContext.serverSocketFactory.createServerSocket(port, BACKLOG, address) as SSLServerSocket
        tls.enabledProtocols = tls.supportedProtocols.filter { it in MODERN_TLS }.toTypedArray()
        tlsSocket = tls
        hub.start(scope)
        acceptJob = scope.launch(ioDispatcher) { acceptLoop(tls, loopbackPort) }

        return WebBridgeEndpoint(address.hostAddress.orEmpty(), tls.localPort, identity.sha256Fingerprint)
    }

    /** Stops listening, drops every connection and client queue, and forgets every session. */
    fun stop() {
        hub.stop()
        sessions.clear()
        tlsSocket.closeQuietly()
        tlsSocket = null
        http?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
        http = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private fun CoroutineScope.acceptLoop(server: ServerSocket, loopbackPort: Int) {
        val slots = Semaphore(MAX_CONNECTIONS)
        while (isActive) {
            val client =
                try {
                    server.accept()
                } catch (e: IOException) {
                    if (isActive && !server.isClosed) Logger.w(e) { "Web bridge accept failed" }
                    return
                }
            if (!slots.tryAcquire()) {
                client.closeQuietly()
                continue
            }
            launch {
                try {
                    proxy(client as SSLSocket, loopbackPort)
                } finally {
                    slots.release()
                }
            }
        }
    }

    /** Pipes one decrypted browser connection to the loopback HTTP server until either side closes or goes idle. */
    private fun CoroutineScope.proxy(client: SSLSocket, loopbackPort: Int) {
        val upstream = Socket()
        try {
            client.soTimeout = IDLE_TIMEOUT_MILLIS
            client.startHandshake()
            upstream.connect(InetSocketAddress(LOOPBACK, loopbackPort), CONNECT_TIMEOUT_MILLIS)
            upstream.soTimeout = IDLE_TIMEOUT_MILLIS
            val toServer = launch {
                pump(client.inputStream, upstream.outputStream) { upstream.shutdownOutputQuietly() }
            }
            pump(upstream.inputStream, client.outputStream) {}
            toServer.cancel()
        } catch (_: SocketTimeoutException) {
            // Idle connection; closing it is the intended outcome.
        } catch (e: IOException) {
            // Untrusted-certificate aborts land here on every first visit; keep them out of warning logs.
            Logger.d { "Web bridge connection ended: ${e::class.simpleName}" }
        } finally {
            upstream.closeQuietly()
            client.closeQuietly()
        }
    }

    private fun pump(input: InputStream, output: OutputStream, onEnd: () -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: SocketException) {
            // The other direction closed the pair.
        } catch (_: SocketTimeoutException) {
            // Idle: fall through and close.
        } finally {
            onEnd()
        }
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
            // Nothing useful to do while tearing down.
        }
    }

    private fun Socket.shutdownOutputQuietly() {
        try {
            if (!isClosed && !isOutputShutdown) shutdownOutput()
        } catch (_: IOException) {
            // Already gone.
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val MODERN_TLS = setOf("TLSv1.3", "TLSv1.2")
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 16
        const val BUFFER_BYTES = 8 * 1024
        const val IDLE_TIMEOUT_MILLIS = 120_000
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}

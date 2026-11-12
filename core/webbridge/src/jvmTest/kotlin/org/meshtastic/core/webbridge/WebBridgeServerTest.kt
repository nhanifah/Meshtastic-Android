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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioTransportWriter
import org.meshtastic.proto.FromRadio
import java.net.InetAddress
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End to end over real TLS: the front end must decrypt and hand each request to the loopback HTTP server. */
class WebBridgeServerTest {

    private val sessions = WebBridgeSessions(clockMillis = System::currentTimeMillis)
    private val server =
        WebBridgeServer(
            hub =
            WebBridgeHub(
                radioFrames = emptyFlow(),
                radioWriter =
                object : RadioTransportWriter {
                    override fun trySendToRadio(bytes: ByteArray) = true
                },
                configDumpProvider =
                object : ConfigDumpProvider {
                    override suspend fun configDump(nonce: Int): List<FromRadio>? = null
                },
                clockMillis = System::currentTimeMillis,
            ),
            sessions = sessions,
            assets = WebClientAssets.NONE,
            ioDispatcher = Dispatchers.IO,
        )

    /** Trusts exactly the certificate the server just issued, the way a person accepting it in a browser does. */
    private fun sslContextTrusting(fingerprint: String): SSLContext {
        val trustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = error("unused")

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val presented = WebBridgeIdentity(SSLContext.getDefault(), chain.first()).sha256Fingerprint
                    check(presented == fingerprint) { "Unexpected certificate" }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    @Test
    fun `serves the pairing page and api over https`() = runBlocking {
        val endpoint = server.start(InetAddress.getByName("127.0.0.1"), port = 0, scope = this)
        try {
            assertTrue(endpoint.port > 0)
            assertEquals(95, endpoint.certificateSha256.length)
            val context = sslContextTrusting(endpoint.certificateSha256)

            fun open(path: String) = (URI("${endpoint.origin}$path").toURL().openConnection() as HttpsURLConnection)
                // Default hostname verification stays on: it proves the certificate names the listen address.
                .apply { sslSocketFactory = context.socketFactory }

            val pair = open("/pair")
            assertEquals(200, pair.responseCode)
            assertContains(pair.inputStream.readBytes().decodeToString(), "Meshtastic Web Access")

            val api = open("/api/v1/fromradio")
            assertEquals(401, api.responseCode)
        } finally {
            server.stop()
        }
    }
}

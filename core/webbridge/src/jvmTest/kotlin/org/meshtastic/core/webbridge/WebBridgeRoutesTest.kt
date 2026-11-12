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

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.emptyFlow
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioTransportWriter
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebBridgeRoutesTest {
    private val sessions = WebBridgeSessions(clockMillis = { 0L })
    private val hub =
        WebBridgeHub(
            radioFrames = emptyFlow(),
            radioWriter =
            object : RadioTransportWriter {
                override fun trySendToRadio(bytes: ByteArray) = true
            },
            configDumpProvider =
            object : ConfigDumpProvider {
                override suspend fun configDump(nonce: Int) =
                    listOf(FromRadio.Builder().also { it.config_complete_id = nonce }.build())
            },
            clockMillis = { 0L },
        )
    private val assets = WebClientAssets { path ->
        when (path) {
            "index.html" -> WebAsset("<h1>client</h1>".encodeToByteArray(), "text/html")
            "app.js" -> WebAsset(byteArrayOf(1, 2), "text/javascript", contentEncoding = "gzip")
            else -> null
        }
    }

    private fun bridgeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { webBridgeModule(hub, sessions, assets) }
        block()
    }

    private suspend fun ApplicationTestBuilder.pair(): String {
        val response = client.post("/pair") { setBody(sessions.issuePairingToken()) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
        assertContains(setCookie, "HttpOnly")
        assertContains(setCookie, "Secure")
        assertContains(setCookie, "SameSite=Strict")
        return setCookie.substringBefore(';')
    }

    @Test
    fun `api calls without a session are refused`() = bridgeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/fromradio").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/toradio") { setBody(byteArrayOf()) }.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/fromradio") { header(HttpHeaders.Cookie, "mwb_session=guess") }.status,
        )
    }

    @Test
    fun `a wrong token does not pair`() = bridgeTest {
        sessions.issuePairingToken()
        val response = client.post("/pair") { setBody("0".repeat(32)) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(null, response.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `a paired browser completes a handshake through the api`() = bridgeTest {
        val cookie = pair()
        val wantConfig = ToRadio.Builder().also { it.want_config_id = 31 }.build().encode()

        val put =
            client.put("/api/v1/toradio") {
                header(HttpHeaders.Cookie, cookie)
                setBody(wantConfig)
            }
        assertEquals(HttpStatusCode.OK, put.status)

        val frame = client.get("/api/v1/fromradio") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(31, FromRadio.ADAPTER.decode(frame.bodyAsBytes()).config_complete_id)
        assertEquals("no-store", frame.headers[HttpHeaders.CacheControl])

        val empty = client.get("/api/v1/fromradio?all=false") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(empty.bodyAsBytes().isEmpty())
    }

    @Test
    fun `oversized toradio bodies are rejected`() = bridgeTest {
        val cookie = pair()
        val response =
            client.put("/api/v1/toradio") {
                header(HttpHeaders.Cookie, cookie)
                setBody(ByteArray(WebBridgeRoutes.MAX_TO_RADIO_BYTES.toInt() + 1))
            }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `static client and pairing page are served`() = bridgeTest {
        assertEquals("<h1>client</h1>", client.get("/").bodyAsText())
        assertContains(client.get("/pair").bodyAsText(), "location.hash")
        assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
        assertEquals("nosniff", client.get("/").headers["X-Content-Type-Options"])
    }

    @Test
    fun `app routes fall back to the client and gzip assets keep their encoding`() = bridgeTest {
        assertEquals("<h1>client</h1>", client.get("/messages/broadcast/0").bodyAsText())
        assertEquals("gzip", client.get("/app.js").headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun `the client's connection probe gets an answer without a session`() = bridgeTest {
        assertEquals(HttpStatusCode.NoContent, client.options("/api/v1/toradio").status)
    }
}

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

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** HTTP surface of the web bridge. Mirrors the firmware HTTP API the Meshtastic web client already speaks. */
internal object WebBridgeRoutes {
    const val COOKIE_NAME = "mwb_session"
    const val PROTOBUF = "application/x-protobuf"

    /** Same ceiling as `StreamFrameCodec.MAX_TO_FROM_RADIO_SIZE`; a `ToRadio` can never be larger. */
    const val MAX_TO_RADIO_BYTES = 512L

    /** A pairing code is 8 digits, perhaps with spaces or dashes; anything much longer is not a code. */
    const val MAX_TOKEN_BODY_BYTES = 128L
}

/** Installs the web bridge routes. Every `/api/v1` call requires the session cookie `/pair` hands out. */
internal fun Application.webBridgeModule(hub: WebBridgeHub, sessions: WebBridgeSessions, assets: WebClientAssets) {
    install(SecurityHeaders)
    routing {
        get("/pair") { call.respondText(PAIR_PAGE_HTML, ContentType.Text.Html) }

        post("/pair") {
            val token = call.readBodyOrNull(WebBridgeRoutes.MAX_TOKEN_BODY_BYTES)?.decodeToString()?.trim()
            val cookie = token?.let(sessions::redeem)
            if (cookie == null) {
                call.respond(HttpStatusCode.Forbidden)
            } else {
                // Written by hand: Ktor refuses a Secure cookie on the plain loopback hop behind the TLS front end.
                call.response.header(
                    HttpHeaders.SetCookie,
                    "${WebBridgeRoutes.COOKIE_NAME}=$cookie; Path=/; HttpOnly; Secure; SameSite=Strict",
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // The web client probes this before connecting; any answer will do, so it needs no session.
        options("/api/v1/toradio") { call.respond(HttpStatusCode.NoContent) }

        get("/api/v1/fromradio") {
            val sessionId = call.sessionIdOrNull(sessions) ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val frame = hub.poll(sessionId) ?: ByteArray(0)
            call.respondBytes(frame, ContentType.parse(WebBridgeRoutes.PROTOBUF))
        }

        put("/api/v1/toradio") {
            val sessionId = call.sessionIdOrNull(sessions) ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val body =
                call.readBodyOrNull(WebBridgeRoutes.MAX_TO_RADIO_BYTES)
                    ?: return@put call.respond(HttpStatusCode.PayloadTooLarge)
            when (hub.submit(sessionId, body)) {
                SubmitResult.ACCEPTED -> call.respond(HttpStatusCode.OK)
                SubmitResult.UNAVAILABLE -> call.respond(HttpStatusCode.ServiceUnavailable)
            }
        }

        get("{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/").ifEmpty { "index.html" }
            if (path.split('/').any { it == ".." || it.startsWith('.') }) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            // Client-side routes such as /messages have no file of their own; the single-page app handles them.
            val isAppRoute = !path.substringAfterLast('/').contains('.')
            // The app page (including / itself) is useless without a session: every API call would fail with 401 and
            // the web client would only say it cannot connect. Send an unpaired browser, such as one that typed the
            // address, to pair first.
            if ((isAppRoute || path == "index.html") && call.sessionIdOrNull(sessions) == null) {
                return@get call.respondRedirect("/pair")
            }
            val asset = assets.open(path) ?: if (isAppRoute) assets.open("index.html") else null
            when {
                asset != null -> {
                    asset.contentEncoding?.let { call.response.header(HttpHeaders.ContentEncoding, it) }
                    call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
                }

                isAppRoute -> call.respondText(NO_CLIENT_PAGE_HTML, ContentType.Text.Html)

                else -> call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private fun ApplicationCall.sessionIdOrNull(sessions: WebBridgeSessions): String? =
    sessions.sessionIdFor(request.cookies[WebBridgeRoutes.COOKIE_NAME])

/** Reads at most [maxBytes]; returns null when the body is larger, so an oversized upload is never buffered. */
private suspend fun ApplicationCall.readBodyOrNull(maxBytes: Long): ByteArray? {
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    return bytes.takeIf { it.size <= maxBytes }
}

/** Tight defaults for every response: no sniffing, no framing, no referrer, and no caching of API traffic. */
private val SecurityHeaders =
    createApplicationPlugin("WebBridgeSecurityHeaders") {
        onCall { call ->
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            if (call.request.local.uri.startsWith("/api/") || call.request.local.uri.startsWith("/pair")) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
            }
        }
    }

/**
 * Pairs this browser, then opens the web client. A scanned QR code carries the one-time code in the URL fragment (never
 * sent to any server), which is used at once and stripped from the address bar and history. A browser that opened the
 * address directly gets a form for the code the phone shows instead.
 */
private val PAIR_PAGE_HTML =
    """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Meshtastic Web Access</title>
    <style>
    body{font:16px system-ui,sans-serif;margin:3rem auto;max-width:32rem;padding:0 1rem}
    input{font:inherit;font-size:1.5rem;letter-spacing:.2em;width:10em;padding:.3em}
    button{font:inherit;padding:.5em 1em;margin-left:.5em}
    </style>
    </head><body>
    <h1>Meshtastic Web Access</h1>
    <p id="status">Enter the pairing code shown on your phone under Settings, Web access.</p>
    <form id="form">
      <input id="code" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="16"
        aria-label="Pairing code" placeholder="1234 5678" required autofocus>
      <button type="submit">Pair</button>
    </form>
    <script>
    (() => {
      const status = document.getElementById("status");
      const form = document.getElementById("form");
      const input = document.getElementById("code");
      async function pair(code) {
        status.textContent = "Pairing with your phone…";
        let res;
        try {
          res = await fetch("/pair", { method: "POST", body: code, credentials: "same-origin" });
        } catch (e) {
          status.textContent = "Could not reach your phone. Check that it is on the same Wi-Fi and Web access is on.";
          return;
        }
        if (res.ok) { location.replace("/"); return; }
        status.textContent = "That code is wrong, expired or was already used. Enter the code your phone shows now.";
        input.value = "";
        input.focus();
      }
      form.addEventListener("submit", (e) => { e.preventDefault(); pair(input.value); });
      const token = new URLSearchParams(location.hash.slice(1)).get("t");
      history.replaceState(null, "", "/pair");
      if (token) pair(token);
    })();
    </script>
    </body></html>
    """
        .trimIndent()

private val NO_CLIENT_PAGE_HTML =
    """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Meshtastic Web Access</title>
    <style>body{font:16px system-ui,sans-serif;margin:3rem auto;max-width:32rem;padding:0 1rem}</style>
    </head><body>
    <h1>Meshtastic Web Access</h1>
    <p>Your phone is serving its radio API at <code>/api/v1</code>, but this build does not include the web client.</p>
    </body></html>
    """
        .trimIndent()

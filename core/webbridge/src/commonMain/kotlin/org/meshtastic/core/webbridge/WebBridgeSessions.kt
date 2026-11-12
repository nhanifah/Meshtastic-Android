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

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.util.platformRandomBytes

/** A paired browser as the phone's UI shows it. Never exposes the cookie itself. */
data class WebBridgeSessionInfo(val id: String, val pairedAtMillis: Long)

/**
 * Pairing and session state for the web bridge.
 *
 * At most one pairing token is live at a time. It is single-use, expires after [tokenTtlMillis], and is burned after
 * [maxFailedAttempts] wrong guesses. Redeeming it yields a random session cookie. The QR code carries the token in the
 * URL fragment, which browsers never send in a request, so it does not appear in logs or `Referer` headers.
 *
 * All values compare in constant time. Nothing here is persisted: stopping the bridge forgets every session.
 */
@Suppress("TooManyFunctions")
class WebBridgeSessions(
    private val clockMillis: () -> Long,
    private val randomBytes: (Int) -> ByteArray = ::platformRandomBytes,
    private val tokenTtlMillis: Long = DEFAULT_TOKEN_TTL_MILLIS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxFailedAttempts: Int = DEFAULT_MAX_FAILED_ATTEMPTS,
) {
    private class PendingToken(val value: String, val expiresAtMillis: Long, var failures: Int = 0)

    private class Session(val cookie: String, val info: WebBridgeSessionInfo)

    private val lock = SynchronizedObject()
    private var pending: PendingToken? = null
    private val sessionsByCookie = LinkedHashMap<String, Session>()

    private val _sessions = MutableStateFlow<List<WebBridgeSessionInfo>>(emptyList())

    /** Paired browsers, oldest first. */
    val sessions: StateFlow<List<WebBridgeSessionInfo>> = _sessions.asStateFlow()

    /** Issues a fresh pairing token and invalidates any earlier one. */
    fun issuePairingToken(): String = synchronized(lock) {
        randomHex(TOKEN_BYTES).also { pending = PendingToken(it, clockMillis() + tokenTtlMillis) }
    }

    /**
     * Trades [token] for a new session cookie, or returns null when the token is wrong, expired or already used. When
     * the session limit is reached, the oldest session is evicted so the person holding the phone can always pair.
     */
    fun redeem(token: String): String? = synchronized(lock) {
        val current = pending ?: return@synchronized null
        if (clockMillis() >= current.expiresAtMillis) {
            pending = null
            return@synchronized null
        }
        if (!constantTimeEquals(current.value, token)) {
            current.failures++
            if (current.failures >= maxFailedAttempts) pending = null
            return@synchronized null
        }
        pending = null
        if (sessionsByCookie.size >= maxSessions) {
            sessionsByCookie.keys.firstOrNull()?.let { sessionsByCookie.remove(it) }
        }
        val cookie = randomHex(COOKIE_BYTES)
        sessionsByCookie[cookie] = Session(cookie, WebBridgeSessionInfo(randomHex(ID_BYTES), clockMillis()))
        publish()
        cookie
    }

    /** Returns the session id for [cookie], or null when it is not a live session. */
    fun sessionIdFor(cookie: String?): String? {
        if (cookie.isNullOrEmpty()) return null
        return synchronized(lock) {
            // Scan every entry so the time taken does not depend on which cookie matched.
            var match: String? = null
            sessionsByCookie.values.forEach { if (constantTimeEquals(it.cookie, cookie)) match = it.info.id }
            match
        }
    }

    /** Ends the session with [id]. Returns true when one was removed. */
    fun revoke(id: String): Boolean = synchronized(lock) {
        val cookie = sessionsByCookie.values.firstOrNull { it.info.id == id }?.cookie ?: return@synchronized false
        sessionsByCookie.remove(cookie)
        publish()
        true
    }

    /** Forgets every session and any pending token. */
    fun clear() = synchronized(lock) {
        pending = null
        sessionsByCookie.clear()
        publish()
    }

    private fun publish() {
        _sessions.value = sessionsByCookie.values.map { it.info }
    }

    private fun randomHex(size: Int): String = randomBytes(size).toByteString().hex()

    companion object {
        const val DEFAULT_TOKEN_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_MAX_SESSIONS = 4
        const val DEFAULT_MAX_FAILED_ATTEMPTS = 5
        private const val TOKEN_BYTES = 16
        private const val COOKIE_BYTES = 32
        private const val ID_BYTES = 4

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            var diff = a.length xor b.length
            for (i in 0 until maxOf(a.length, b.length)) {
                diff = diff or (a.getOrElse(i) { ' ' }.code xor b.getOrElse(i) { ' ' }.code)
            }
            return diff == 0
        }
    }
}

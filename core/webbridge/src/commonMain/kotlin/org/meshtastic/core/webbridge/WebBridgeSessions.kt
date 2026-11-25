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
 * A pairing token is a short numeric code, so it can be typed on a computer that cannot scan the QR code. Each code is
 * single-use, expires after [tokenTtlMillis], and is burned after [maxFailedAttempts] wrong guesses, which keeps an
 * 8-digit code far out of reach of guessing. Redeeming it yields a random session cookie. The QR code carries the code
 * in the URL fragment, which browsers never send in a request, so it does not appear in logs or `Referer` headers.
 *
 * At most two codes are live: an automatic rotation keeps the previous code valid until it expires, so a code someone
 * is typing is not cut off, while an explicit new code and every successful pairing invalidate all earlier ones.
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
    private val pending = ArrayDeque<PendingToken>()
    private val sessionsByCookie = LinkedHashMap<String, Session>()

    private val _sessions = MutableStateFlow<List<WebBridgeSessionInfo>>(emptyList())

    /** Paired browsers, oldest first. */
    val sessions: StateFlow<List<WebBridgeSessionInfo>> = _sessions.asStateFlow()

    /**
     * Issues a fresh pairing code. It invalidates every earlier code unless [keepPrevious] is set, in which case the
     * most recent earlier code stays valid until its own expiry.
     */
    fun issuePairingToken(keepPrevious: Boolean = false): String = synchronized(lock) {
        val now = clockMillis()
        pending.removeAll { now >= it.expiresAtMillis }
        if (keepPrevious) {
            while (pending.size > 1) pending.removeFirst()
        } else {
            pending.clear()
        }
        randomDigits(TOKEN_DIGITS).also { pending.addLast(PendingToken(it, now + tokenTtlMillis)) }
    }

    /**
     * Trades [token] for a new session cookie, or returns null when the token is wrong, expired or already used. When
     * the session limit is reached, the oldest session is evicted so the person holding the phone can always pair.
     */
    fun redeem(token: String): String? = synchronized(lock) {
        val now = clockMillis()
        pending.removeAll { now >= it.expiresAtMillis }
        val candidate = normalizeCode(token)
        // Compare against every live code so the time taken does not depend on which one matched.
        var matched = false
        pending.forEach { if (constantTimeEquals(it.value, candidate)) matched = true }
        if (!matched) {
            pending.forEach { it.failures++ }
            pending.removeAll { it.failures >= maxFailedAttempts }
            return@synchronized null
        }
        pending.clear()
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
        pending.clear()
        sessionsByCookie.clear()
        publish()
    }

    private fun publish() {
        _sessions.value = sessionsByCookie.values.map { it.info }
    }

    private fun randomHex(size: Int): String = randomBytes(size).toByteString().hex()

    /** Uniform decimal digits: bytes of 250 and above are rejected so `% 10` has no bias. */
    private fun randomDigits(count: Int): String = buildString {
        while (length < count) {
            randomBytes(count).forEach { byte ->
                val value = byte.toInt() and BYTE_MASK
                if (value < UNBIASED_BYTE_LIMIT && length < count) append('0' + value % DECIMAL_BASE)
            }
        }
    }

    companion object {
        const val DEFAULT_TOKEN_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_MAX_SESSIONS = 4
        const val DEFAULT_MAX_FAILED_ATTEMPTS = 5

        /** 10^8 codes against at most [DEFAULT_MAX_FAILED_ATTEMPTS] guesses per code. */
        const val TOKEN_DIGITS = 8
        private const val BYTE_MASK = 0xFF
        private const val UNBIASED_BYTE_LIMIT = 250
        private const val DECIMAL_BASE = 10
        private const val COOKIE_BYTES = 32
        private const val ID_BYTES = 4

        /** People type codes with spaces or dashes ("1234 5678"); only the digits count. */
        fun normalizeCode(input: String): String = input.filter { it in '0'..'9' }

        /** Shows a code in two groups of four, the way people read it off a screen. */
        fun formatCode(code: String): String = code.chunked(CODE_GROUP).joinToString(" ")

        private const val CODE_GROUP = 4

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            var diff = a.length xor b.length
            for (i in 0 until maxOf(a.length, b.length)) {
                diff = diff or (a.getOrElse(i) { ' ' }.code xor b.getOrElse(i) { ' ' }.code)
            }
            return diff == 0
        }
    }
}

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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebBridgeSessionsTest {
    private var now = 0L
    private var counter = 0
    private val sessions =
        WebBridgeSessions(
            clockMillis = { now },
            // Deterministic but distinct bytes per call.
            randomBytes = { size -> ByteArray(size) { (counter++ % 251).toByte() } },
            tokenTtlMillis = 1_000,
            maxSessions = 2,
            maxFailedAttempts = 3,
        )

    @Test
    fun `a token is single use`() {
        val token = sessions.issuePairingToken()
        val cookie = sessions.redeem(token)

        assertNotNull(cookie)
        assertNotNull(sessions.sessionIdFor(cookie))
        assertNull(sessions.redeem(token))
    }

    @Test
    fun `an expired token is refused`() {
        val token = sessions.issuePairingToken()
        now = 1_000

        assertNull(sessions.redeem(token))
    }

    @Test
    fun `issuing a new token invalidates the old one`() {
        val first = sessions.issuePairingToken()
        val second = sessions.issuePairingToken()

        assertNull(sessions.redeem(first))
        assertNotNull(sessions.redeem(second))
    }

    @Test
    fun `an automatic rotation keeps the previous code until it expires`() {
        val first = sessions.issuePairingToken()
        now = 600
        val second = sessions.issuePairingToken(keepPrevious = true)

        assertNotNull(sessions.redeem(first))
        // A successful pairing uses up every live code, not only the one that matched.
        assertNull(sessions.redeem(second))
    }

    @Test
    fun `a rotated-out code still expires on time`() {
        val first = sessions.issuePairingToken()
        now = 600
        val second = sessions.issuePairingToken(keepPrevious = true)
        now = 1_000

        assertNull(sessions.redeem(first))
        assertNotNull(sessions.redeem(second))
    }

    @Test
    fun `only the latest earlier code survives a rotation`() {
        val first = sessions.issuePairingToken()
        val second = sessions.issuePairingToken(keepPrevious = true)
        val third = sessions.issuePairingToken(keepPrevious = true)

        assertNull(sessions.redeem(first))
        assertNotNull(sessions.redeem(second))
        assertNull(sessions.redeem(third))
    }

    @Test
    fun `codes are eight digits and typed separators are ignored`() {
        val code = sessions.issuePairingToken()

        assertEquals(WebBridgeSessions.TOKEN_DIGITS, code.length)
        assertTrue(code.all { it in '0'..'9' })
        assertEquals("${code.take(4)} ${code.drop(4)}", WebBridgeSessions.formatCode(code))
        assertNotNull(sessions.redeem(" ${code.take(4)}-${code.drop(4)} "))
    }

    @Test
    fun `codes have no modulo bias toward low digits`() {
        // Bytes 250..255 must be skipped, not folded onto 0..5.
        var next = 0
        val biased =
            WebBridgeSessions(
                clockMillis = { 0L },
                randomBytes = { size -> ByteArray(size) { (if (next++ % 2 == 0) 255 else 7).toByte() } },
            )

        assertEquals("77777777", biased.issuePairingToken())
    }

    @Test
    fun `repeated wrong guesses burn the token`() {
        val token = sessions.issuePairingToken()
        repeat(3) { assertNull(sessions.redeem("wrong")) }

        assertNull(sessions.redeem(token))
    }

    @Test
    fun `unknown or missing cookies have no session`() {
        assertNull(sessions.sessionIdFor(null))
        assertNull(sessions.sessionIdFor(""))
        assertNull(sessions.sessionIdFor("not-a-session"))
    }

    @Test
    fun `the oldest session is evicted at the limit`() {
        val first = sessions.redeem(sessions.issuePairingToken())
        sessions.redeem(sessions.issuePairingToken())
        sessions.redeem(sessions.issuePairingToken())

        assertNull(sessions.sessionIdFor(first))
        assertEquals(2, sessions.sessions.value.size)
    }

    @Test
    fun `revoke and clear end sessions`() {
        val cookie = sessions.redeem(sessions.issuePairingToken())
        val id = sessions.sessionIdFor(cookie)!!

        assertTrue(sessions.revoke(id))
        assertFalse(sessions.revoke(id))
        assertNull(sessions.sessionIdFor(cookie))

        val other = sessions.redeem(sessions.issuePairingToken())
        sessions.clear()
        assertNull(sessions.sessionIdFor(other))
        assertTrue(sessions.sessions.value.isEmpty())
    }

    @Test
    fun `constant time comparison matches plain equality`() {
        assertTrue(WebBridgeSessions.constantTimeEquals("abc", "abc"))
        assertFalse(WebBridgeSessions.constantTimeEquals("abc", "abd"))
        assertFalse(WebBridgeSessions.constantTimeEquals("abc", "abc "))
        assertNotEquals(sessions.issuePairingToken(), sessions.issuePairingToken())
    }
}

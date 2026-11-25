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

import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SingleIdentityKeyManagerTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private
    private val chain = arrayOf<X509Certificate>()
    private val manager = SingleIdentityKeyManager(key, chain)

    @Test
    fun `offers the identity only for EC suites`() {
        val alias = assertNotNull(manager.chooseServerAlias("EC", null, null))
        assertNotNull(manager.chooseEngineServerAlias("EC_EC", null, null))
        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertNull(manager.getServerAliases("RSA", null))

        assertSame(key, manager.getPrivateKey(alias))
        assertContentEquals(chain, manager.getCertificateChain(alias))
    }

    @Test
    fun `unknown aliases and client roles get nothing`() {
        assertNull(manager.getPrivateKey("other"))
        assertNull(manager.getCertificateChain("other"))
        assertNull(manager.chooseClientAlias(arrayOf("EC"), null, null))
        assertNull(manager.getClientAliases("EC", null))
    }
}

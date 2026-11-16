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
package org.meshtastic.core.network.radio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.repository.PhoneRelayLink
import org.meshtastic.core.repository.RadioTransportCallback
import org.meshtastic.core.repository.TransportDisconnectReason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneRelayRadioTransportTest {

    private class FakeLink : PhoneRelayLink {
        override val isAvailable = MutableStateFlow(false)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val frames: Flow<ByteArray> = incoming.receiveAsFlow()
        val sent = mutableListOf<ByteArray>()
        var opened = 0
        var closed = 0

        override fun open() {
            opened++
        }

        override fun close() {
            closed++
        }

        override suspend fun send(bytes: ByteArray): Boolean {
            sent += bytes
            return true
        }
    }

    private class RecordingCallback : RadioTransportCallback {
        var connects = 0
        val disconnects = mutableListOf<Boolean>()
        val received = mutableListOf<ByteArray>()

        override fun onConnect() {
            connects++
        }

        override fun onDisconnect(isPermanent: Boolean, errorMessage: String?, reason: TransportDisconnectReason?) {
            disconnects += isPermanent
        }

        override fun handleFromRadio(bytes: ByteArray) {
            received += bytes
        }
    }

    @Test
    fun connectsOnlyOnceThePhoneHasARadio() = runTest {
        val link = FakeLink()
        val callback = RecordingCallback()
        val transport = PhoneRelayRadioTransport(callback, backgroundScope, link)
        transport.start()
        runCurrent()

        assertEquals(1, link.opened)
        assertEquals(0, callback.connects)
        assertFalse(transport.handleSendToRadio(byteArrayOf(1)), "nothing to send to before the phone is ready")
        assertTrue(callback.disconnects.isEmpty(), "never connected, so nothing to report as lost")

        link.isAvailable.value = true
        runCurrent()
        assertEquals(1, callback.connects)
    }

    @Test
    fun relaysFramesBothWays() = runTest {
        val link = FakeLink()
        val callback = RecordingCallback()
        val transport = PhoneRelayRadioTransport(callback, backgroundScope, link)
        transport.start()
        link.isAvailable.value = true
        runCurrent()

        link.incoming.send(byteArrayOf(7, 8))
        assertTrue(transport.handleSendToRadio(byteArrayOf(3)))
        runCurrent()

        assertContentEquals(byteArrayOf(7, 8), callback.received.single())
        assertContentEquals(byteArrayOf(3), link.sent.single())
    }

    @Test
    fun losingThePhoneIsATransientDisconnect() = runTest {
        val link = FakeLink()
        val callback = RecordingCallback()
        val transport = PhoneRelayRadioTransport(callback, backgroundScope, link)
        transport.start()
        link.isAvailable.value = true
        runCurrent()

        link.isAvailable.value = false
        runCurrent()
        assertEquals(listOf(false), callback.disconnects)
        assertFalse(transport.handleSendToRadio(byteArrayOf(1)))

        link.isAvailable.value = true
        runCurrent()
        assertEquals(2, callback.connects)

        transport.close()
        assertEquals(listOf(false, true), callback.disconnects)
        assertEquals(1, link.closed)
    }
}

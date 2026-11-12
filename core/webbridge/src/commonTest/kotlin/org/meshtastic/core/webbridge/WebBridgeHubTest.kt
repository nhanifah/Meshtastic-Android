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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.RadioTransportWriter
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebBridgeHubTest {
    private val radioFrames = MutableSharedFlow<ByteString>(extraBufferCapacity = 64)
    private val sent = mutableListOf<ByteArray>()
    private var radioAccepts = true
    private val writer =
        object : RadioTransportWriter {
            override fun trySendToRadio(bytes: ByteArray): Boolean = radioAccepts.also { if (it) sent += bytes }
        }
    private var dumpAvailable = true
    private val dumps =
        object : ConfigDumpProvider {
            override suspend fun configDump(nonce: Int): List<FromRadio>? = if (!dumpAvailable) {
                null
            } else {
                listOf(
                    FromRadio.Builder().also { it.id = 1 }.build(),
                    FromRadio.Builder().also { it.config_complete_id = nonce }.build(),
                )
            }
        }

    private fun TestScope.hub(capacity: Int = 4): WebBridgeHub = WebBridgeHub(
        radioFrames = radioFrames,
        radioWriter = writer,
        configDumpProvider = dumps,
        clockMillis = { testScheduler.currentTime },
        liveQueueCapacity = capacity,
        idleTimeoutMillis = 10_000,
    )

    private fun toRadio(build: ToRadio.Builder.() -> Unit) = ToRadio.Builder().apply(build).build().encode()

    @Test
    fun `want_config is answered from the provider and never reaches the radio`() = runTest {
        val hub = hub()

        assertEquals(SubmitResult.ACCEPTED, hub.submit("a", toRadio { want_config_id = 77 }))

        assertTrue(sent.isEmpty())
        assertEquals(1, FromRadio.ADAPTER.decode(hub.poll("a")!!).id)
        assertEquals(77, FromRadio.ADAPTER.decode(hub.poll("a")!!).config_complete_id)
        assertNull(hub.poll("a"))
    }

    @Test
    fun `want_config before the phone has a config is unavailable`() = runTest {
        dumpAvailable = false

        assertEquals(SubmitResult.UNAVAILABLE, hub().submit("a", toRadio { want_config_id = 1 }))
    }

    @Test
    fun `packets are forwarded and a refused send is unavailable`() = runTest {
        val hub = hub()
        val bytes = toRadio { packet = MeshPacket.Builder().also { it.to = 5 }.build() }

        assertEquals(SubmitResult.ACCEPTED, hub.submit("a", bytes))
        assertContentEquals(bytes, sent.single())

        radioAccepts = false
        assertEquals(SubmitResult.UNAVAILABLE, hub.submit("a", bytes))
    }

    @Test
    fun `live frames fan out to every polling client and drop oldest when full`() = runTest {
        val hub = hub(capacity = 2)
        hub.start(backgroundScope)
        runCurrent()
        hub.poll("a")
        hub.poll("b")

        listOf(1, 2, 3).forEach { radioFrames.emit(byteArrayOf(it.toByte()).toByteString()) }
        runCurrent()

        assertContentEquals(byteArrayOf(2), hub.poll("a"))
        assertContentEquals(byteArrayOf(3), hub.poll("a"))
        assertNull(hub.poll("a"))
        assertContentEquals(byteArrayOf(2), hub.poll("b"))
    }

    @Test
    fun `a dump is served before queued live frames and is never trimmed`() = runTest {
        val hub = hub(capacity = 1)
        hub.start(backgroundScope)
        runCurrent()
        hub.poll("a")
        radioFrames.emit(byteArrayOf(9).toByteString())
        runCurrent()

        hub.submit("a", toRadio { want_config_id = 5 })

        assertEquals(1, FromRadio.ADAPTER.decode(hub.poll("a")!!).id)
        assertEquals(5, FromRadio.ADAPTER.decode(hub.poll("a")!!).config_complete_id)
        assertContentEquals(byteArrayOf(9), hub.poll("a"))
    }

    @Test
    fun `idle clients are dropped`() = runTest {
        val hub = hub()
        hub.start(backgroundScope)
        hub.poll("a")
        assertEquals(1, hub.activeClientCount)

        advanceTimeBy(20_001)
        runCurrent()

        assertEquals(0, hub.activeClientCount)
        hub.stop()
    }
}

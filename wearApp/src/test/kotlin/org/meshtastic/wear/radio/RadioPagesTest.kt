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
package org.meshtastic.wear.radio

import org.meshtastic.core.model.Node
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioPagesTest {

    private val now = 1_700_000_000_000L

    private fun node(num: Int, short: String, lastHeardSec: Int = 0, snr: Float = Float.MAX_VALUE) = Node(
        num = num,
        user =
        User.Builder()
            .also {
                it.short_name = short
                it.long_name = "$short long"
                it.id = "!0000000$num"
            }
            .build(),
        lastHeard = lastHeardSec,
        snr = snr,
    )

    private fun RadioPage.value(label: String) = lines.firstOrNull { it.label == label }?.value

    @Test
    fun `status frame shows battery, node counts, airtime and uptime like the OLED`() {
        val me =
            node(1, "ME")
                .copy(
                    deviceMetrics =
                    DeviceMetrics.Builder()
                        .also {
                            it.battery_level = 87
                            it.voltage = 4.05f
                        }
                        .build(),
                )
        val stats =
            LocalStats.Builder()
                .also {
                    it.uptime_seconds = 3 * 3600 + 5 * 60
                    it.channel_utilization = 13.25f
                    it.air_util_tx = 2.1f
                }
                .build()

        val status = radioPages(RadioSnapshot(ourNode = me, stats = stats, onlineNodes = 9, totalNodes = 12)).first()

        assertEquals("ME ME long", status.title)
        assertEquals("87% 4.05V", status.value("Batt"))
        assertEquals("9/12 online", status.value("Nodes"))
        assertEquals("13.3%", status.value("ChUtil"))
        assertEquals("2.1%", status.value("AirTx"))
        assertEquals("No fix", status.value("GPS"))
        assertEquals("3h 05m", status.value("Up"))
    }

    @Test
    fun `nodes frame lists recent nodes with age and SNR`() {
        val other = node(2, "BASE", lastHeardSec = ((now / 1000) - 120).toInt(), snr = 9f)
        val nodes =
            radioPages(RadioSnapshot(nodes = listOf(other), onlineNodes = 1, totalNodes = 2, nowMillis = now))[1]

        assertEquals("Nodes 1/2", nodes.title)
        assertEquals("2m 9.0dB", nodes.value("BASE"))
        assertNull(nodes.text)
    }

    @Test
    fun `lora frame names region and preset the way the radio does`() {
        val lora =
            Config.LoRaConfig.Builder()
                .also {
                    it.use_preset = true
                    it.modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST
                    it.region = Config.LoRaConfig.RegionCode.US
                    it.hop_limit = 3
                }
                .build()
        val page =
            radioPages(RadioSnapshot(lora = lora, channelName = "LongFast", frequencyMhz = 906.875f)).first {
                it.title == "LoRa"
            }

        assertEquals("US", page.value("Region"))
        assertEquals("LongFast", page.value("Preset"))
        assertEquals("906.875 MHz", page.value("Freq"))
        assertEquals("3", page.value("Hops"))
    }

    @Test
    fun `environment frame appears only when the radio has a sensor`() {
        val plain = node(1, "ME")
        assertTrue(radioPages(RadioSnapshot(ourNode = plain)).none { it.title == "Environment" })

        val sensing =
            plain.copy(environmentMetrics = EnvironmentMetrics.Builder().also { it.temperature = 18.5f }.build())
        val env = radioPages(RadioSnapshot(ourNode = sensing)).first { it.title == "Environment" }
        assertEquals("18.5°C", env.value("Temp"))
    }

    @Test
    fun `position frame without a fix says so`() {
        val page = radioPages(RadioSnapshot(ourNode = node(1, "ME"))).first { it.title == "Position" }
        assertEquals("No GPS fix", page.text)

        val fixed =
            node(1, "ME")
                .copy(
                    position =
                    Position.Builder()
                        .also {
                            it.latitude_i = -62_000_000
                            it.longitude_i = 1_068_000_000
                            it.sats_in_view = 7
                        }
                        .build(),
                )
        val withFix = radioPages(RadioSnapshot(ourNode = fixed)).first { it.title == "Position" }
        assertEquals("-6.20000", withFix.value("Lat"))
        assertEquals("7", withFix.value("Sats"))
    }

    @Test
    fun `formatters`() {
        assertEquals("Powered 4.20V", battery(101, 4.2f))
        assertEquals("--", battery(null, null))
        assertEquals("2d 3h", duration(2 * 86_400L + 3 * 3600L))
        assertEquals("45s", duration(45))
        assertEquals("ClientMute", "CLIENT_MUTE".toPresetName())
    }
}

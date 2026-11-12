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
package org.meshtastic.core.data.webbridge

import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.HandshakeConstants
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.meshtastic.proto.MyNodeInfo as ProtoMyNodeInfo
import org.meshtastic.proto.NodeInfo as ProtoNodeInfo

class SyntheticConfigDumpTest {

    private val snapshot =
        ConfigDumpSnapshot(
            myInfo = ProtoMyNodeInfo.Builder().also { it.my_node_num = 1 }.build(),
            ownNode = ProtoNodeInfo.Builder().also { it.num = 1 }.build(),
            metadata = DeviceMetadata.Builder().also { it.firmware_version = "2.7.0" }.build(),
            channels = ChannelSet.Builder().build().toChannelProtos(),
            configs =
            LocalConfig.Builder().also { it.lora = Config.LoRaConfig.Builder().build() }.build().toConfigProtos(),
            moduleConfigs =
            LocalModuleConfig.Builder()
                .also { it.mqtt = ModuleConfig.MQTTConfig.Builder().build() }
                .build()
                .toModuleConfigProtos(),
            otherNodes = listOf(2, 3).map { n -> ProtoNodeInfo.Builder().also { it.num = n }.build() },
        )

    private fun FromRadio.kind(): String = when {
        my_info != null -> "my_info"
        node_info != null -> "node_info:${node_info!!.num}"
        metadata != null -> "metadata"
        channel != null -> "channel"
        config != null -> "config"
        moduleConfig != null -> "moduleConfig"
        config_complete_id != null -> "complete:$config_complete_id"
        else -> "other"
    }

    @Test
    fun `full dump follows firmware order and echoes the nonce`() {
        val kinds = SyntheticConfigDump.frames(nonce = 1234, snapshot = snapshot).map { it.kind() }

        val expected =
            listOf("my_info", "node_info:1", "metadata") +
                List(CHANNEL_SLOT_COUNT) { "channel" } +
                listOf("config", "moduleConfig", "node_info:2", "node_info:3", "complete:1234")
        assertEquals(expected, kinds)
    }

    @Test
    fun `config-only nonce carries no node_info`() {
        val kinds = SyntheticConfigDump.frames(HandshakeConstants.CONFIG_NONCE, snapshot).map { it.kind() }

        assertTrue(kinds.none { it.startsWith("node_info") })
        assertEquals("my_info", kinds.first())
        assertEquals("complete:${HandshakeConstants.CONFIG_NONCE}", kinds.last())
    }

    @Test
    fun `node-info nonce carries only node_info`() {
        val kinds = SyntheticConfigDump.frames(HandshakeConstants.NODE_INFO_NONCE, snapshot).map { it.kind() }

        assertEquals(
            listOf("node_info:1", "node_info:2", "node_info:3", "complete:${HandshakeConstants.NODE_INFO_NONCE}"),
            kinds,
        )
    }

    @Test
    fun `channel slots are primary then secondary then disabled`() {
        val settings = List(2) { i -> ChannelSettings.Builder().also { it.name = "ch$i" }.build() }
        val channels = ChannelSet.Builder().also { it.settings = settings }.build().toChannelProtos()

        assertEquals(CHANNEL_SLOT_COUNT, channels.size)
        assertEquals(Channel.Role.PRIMARY, channels[0].role)
        assertEquals(Channel.Role.SECONDARY, channels[1].role)
        assertTrue(channels.drop(2).all { it.role == Channel.Role.DISABLED })
        assertEquals((0 until CHANNEL_SLOT_COUNT).toList(), channels.map { it.index })
    }

    @Test
    fun `node mapper drops app-only decoration and unknown values`() {
        val node =
            Node(
                num = 7,
                user = User.Builder().also { it.long_name = "Bridge (MQTT)" }.build(),
                viaMqtt = true,
                hopsAway = -1,
                displayName = "my private label",
                notes = "private note",
            )

        val proto = node.toNodeInfoProto()

        assertEquals("Bridge", proto.user?.long_name)
        assertNull(proto.hops_away)
        assertNull(proto.position)
        assertNull(proto.device_metrics)
        assertTrue(proto.via_mqtt)
    }

    @Test
    fun `my info mapper restores device id bytes from hex`() {
        val model =
            MyNodeInfo(
                myNodeNum = 9,
                hasGPS = false,
                model = null,
                firmwareVersion = null,
                couldUpdate = false,
                shouldUpdate = false,
                currentPacketId = 0,
                messageTimeoutMsec = 0,
                minAppVersion = 30200,
                maxChannels = 8,
                hasWifi = false,
                channelUtilization = 0f,
                airUtilTx = 0f,
                deviceId = "abc".encodeUtf8().hex(),
                pioEnv = "tbeam",
            )

        val proto = model.toProto()

        assertEquals(9, proto.my_node_num)
        assertEquals(30200, proto.min_app_version)
        assertEquals("abc".encodeUtf8(), proto.device_id)
        assertEquals("tbeam", proto.pio_env)
    }
}

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

import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single
import org.meshtastic.core.repository.ConfigDumpProvider
import org.meshtastic.core.repository.HandshakeConstants
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.FileInfo
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo as ProtoMyNodeInfo
import org.meshtastic.proto.NodeInfo as ProtoNodeInfo

/** Everything a config dump carries, already in proto form. Built by [SyntheticConfigDumpSource]. */
data class ConfigDumpSnapshot(
    val myInfo: ProtoMyNodeInfo,
    val ownNode: ProtoNodeInfo?,
    val metadata: DeviceMetadata?,
    val deviceUiConfig: DeviceUIConfig? = null,
    val regionPresets: LoRaRegionPresetMap? = null,
    val channels: List<Channel>,
    val configs: List<Config>,
    val moduleConfigs: List<ModuleConfig>,
    val otherNodes: List<ProtoNodeInfo>,
    val fileManifest: List<FileInfo> = emptyList(),
) {
    /** Never print node identities or keys into logs. */
    override fun toString(): String = "ConfigDumpSnapshot(channels=${channels.size}, configs=${configs.size}, " +
        "moduleConfigs=${moduleConfigs.size}, otherNodes=${otherNodes.size})"
}

/**
 * Builds the `FromRadio` sequence a radio sends in answer to `want_config_id`, from a [ConfigDumpSnapshot].
 *
 * The order follows firmware `PhoneAPI` states: my_info, UI config, own node_info, metadata, region presets, channels,
 * config, module config, other node_infos, file manifest, then `config_complete_id` echoing the caller's nonce.
 *
 * The app's own two special nonces split that dump the same way firmware and `MockRadioTransport` do: the config-only
 * nonce carries no `node_info` at all, and the node-info nonce carries only `node_info`. Any other nonce (what the web
 * client sends) gets the whole dump.
 */
object SyntheticConfigDump {

    fun frames(nonce: Int, snapshot: ConfigDumpSnapshot): List<FromRadio> = buildList {
        val wantsConfig = nonce != HandshakeConstants.NODE_INFO_NONCE
        val wantsNodes = nonce != HandshakeConstants.CONFIG_NONCE

        if (wantsConfig) {
            add(FromRadio.Builder().also { it.my_info = snapshot.myInfo }.build())
            snapshot.deviceUiConfig?.let { v -> add(FromRadio.Builder().also { it.deviceuiConfig = v }.build()) }
        }
        if (wantsNodes) {
            snapshot.ownNode?.let { v -> add(FromRadio.Builder().also { it.node_info = v }.build()) }
        }
        if (wantsConfig) {
            snapshot.metadata?.let { v -> add(FromRadio.Builder().also { it.metadata = v }.build()) }
            snapshot.regionPresets?.let { v -> add(FromRadio.Builder().also { it.region_presets = v }.build()) }
            snapshot.channels.forEach { v -> add(FromRadio.Builder().also { it.channel = v }.build()) }
            snapshot.configs.forEach { v -> add(FromRadio.Builder().also { it.config = v }.build()) }
            snapshot.moduleConfigs.forEach { v -> add(FromRadio.Builder().also { it.moduleConfig = v }.build()) }
        }
        if (wantsNodes) {
            snapshot.otherNodes.forEach { v -> add(FromRadio.Builder().also { it.node_info = v }.build()) }
        }
        if (wantsConfig) {
            snapshot.fileManifest.forEach { v -> add(FromRadio.Builder().also { it.fileInfo = v }.build()) }
        }
        add(FromRadio.Builder().also { it.config_complete_id = nonce }.build())
    }
}

/** Reads the app's stored radio state into a [ConfigDumpSnapshot]. */
@Single
class SyntheticConfigDumpSource(
    private val nodeRepository: NodeRepository,
    private val radioConfigRepository: RadioConfigRepository,
) : ConfigDumpProvider {
    override suspend fun configDump(nonce: Int): List<FromRadio>? =
        snapshot()?.let { SyntheticConfigDump.frames(nonce, it) }

    /**
     * Returns the current snapshot, or null until the phone has finished its own handshake (no `MyNodeInfo` yet), in
     * which case there is nothing truthful to answer with.
     */
    suspend fun snapshot(): ConfigDumpSnapshot? {
        val myInfo = nodeRepository.myNodeInfo.value ?: return null
        val nodes = nodeRepository.nodeDBbyNum.value
        val ownNode = nodes[myInfo.myNodeNum]
        return ConfigDumpSnapshot(
            myInfo = myInfo.toProto(),
            ownNode = ownNode?.toNodeInfoProto(),
            metadata = ownNode?.metadata,
            deviceUiConfig = radioConfigRepository.deviceUIConfigFlow.first(),
            regionPresets = radioConfigRepository.loraRegionPresetMapFlow.first(),
            channels = radioConfigRepository.channelSetFlow.first().toChannelProtos(),
            configs = radioConfigRepository.localConfigFlow.first().toConfigProtos(),
            moduleConfigs = radioConfigRepository.moduleConfigFlow.first().toModuleConfigProtos(),
            otherNodes = nodes.values.filter { it.num != myInfo.myNodeNum }.map { it.toNodeInfoProto() },
            fileManifest = radioConfigRepository.fileManifestFlow.first(),
        )
    }
}

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
package org.meshtastic.core.network.repository

import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.MqttLogLevel
import org.meshtastic.mqtt.plus
import org.meshtastic.mqtt.transport.tcp.TcpTransportFactory
import org.meshtastic.mqtt.transport.ws.WebSocketTransportFactory
import org.meshtastic.proto.ModuleConfig
import kotlin.uuid.Uuid

/** The public broker firmware and the proxy fall back to when the radio's MQTT address is empty. */
const val DEFAULT_MQTT_SERVER_ADDRESS: String = "mqtt.meshtastic.org"

/** An MQTT client and the endpoint it should connect to. */
class RadioMqttClient(val client: MqttClient, val endpoint: MqttEndpoint, val address: String)

/**
 * A second MQTT client to **the radio's own MQTT server**: the same address, credentials and TLS the radio and the
 * client proxy use (Settings → MQTT), so a feature that talks to the broker directly never asks the user for a server
 * of its own. The client id is unique per call ([clientIdPrefix] plus a random suffix), so it never evicts the proxy's
 * session.
 */
fun radioMqttClient(clientIdPrefix: String, config: ModuleConfig.MQTTConfig?): RadioMqttClient {
    val rawAddress = config?.address?.ifEmpty { DEFAULT_MQTT_SERVER_ADDRESS } ?: DEFAULT_MQTT_SERVER_ADDRESS
    val endpoint = resolveEndpoint(rawAddress, effectiveTlsEnabled(rawAddress, config?.tls_enabled == true))
    val client =
        MqttClient("$clientIdPrefix-${Uuid.random()}") {
            val tls = mqttTlsConfig()
            transportFactory = TcpTransportFactory(tls) + WebSocketTransportFactory(tls)
            keepAliveSeconds = MQTT_KEEPALIVE_SECONDS
            autoReconnect = true
            val (user, pass) = effectiveCredentials(config)
            username = user
            pass?.let { password(it) }
            logger = KermitMqttLogger()
            // WARN: the library logs topic strings at INFO, and direct-message topics carry node ids.
            logLevel = MqttLogLevel.WARN
        }
    return RadioMqttClient(client, endpoint, rawAddress)
}

/** True when [address] is the public community broker, where only channels with a private key are private. */
fun isPublicMqttServer(address: String): Boolean = extractHost(address).equals(DEFAULT_MQTT_SERVER_ADDRESS, true)

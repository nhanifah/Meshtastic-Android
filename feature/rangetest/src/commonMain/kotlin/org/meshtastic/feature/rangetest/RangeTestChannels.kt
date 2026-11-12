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
package org.meshtastic.feature.rangetest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.util.getChannelUrl
import org.meshtastic.core.model.util.toChannelSet
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioController
import org.meshtastic.core.ui.util.getChannelList
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.core.model.Channel as ModelChannel

/** One channel slot of the connected radio, as the wizard lists it. */
data class RangeTestChannelOption(
    val index: Int,
    val name: String,
    val isPrimary: Boolean,
    val settings: ChannelSettings,
)

/** Why a channel could not be added or joined. */
class RangeTestChannelException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        NO_FREE_SLOT,
        INVALID_CODE,
    }
}

/**
 * The dedicated range-test channel: created in a free secondary slot (the primary channel is never replaced), shared by
 * QR code, joined on the other radio by scanning that code, and removed again when the test is over.
 */
@Single
class RangeTestChannels(
    private val radioConfigRepository: RadioConfigRepository,
    private val radioController: RadioController,
) {
    val channels: Flow<List<RangeTestChannelOption>> =
        radioConfigRepository.channelSetFlow.map { set -> set.toOptions() }

    /** The index of the channel named [name], creating it with a fresh random key in a free slot when missing. */
    suspend fun ensureChannel(name: String = DEFAULT_NAME): Int {
        val set = radioConfigRepository.channelSetFlow.first()
        set.settings
            .indexOfFirst { it.name == name }
            .takeIf { it >= 0 }
            ?.let {
                return it
            }
        val settings =
            ChannelSettings.Builder()
                .also { wb ->
                    wb.name = name
                    wb.psk = ModelChannel.getRandomKey()
                }
                .build()
        return addChannel(set, settings)
    }

    /**
     * Joins the channel in a scanned code: reuses a slot that already has the same name and key, otherwise adds it to a
     * free secondary slot. Returns the channel index.
     */
    suspend fun joinFromCode(code: String): Int {
        val shared =
            runCatching { CommonUri.parse(code.trim()).toChannelSet() }.getOrNull()?.settings?.firstOrNull()
                ?: throw RangeTestChannelException(RangeTestChannelException.Reason.INVALID_CODE)
        val set = radioConfigRepository.channelSetFlow.first()
        set.settings
            .indexOfFirst { it.name == shared.name && it.psk == shared.psk }
            .takeIf { it >= 0 }
            ?.let {
                return it
            }
        return addChannel(set, shared)
    }

    /** A Meshtastic channel link holding only [settings], for the QR code the other radio scans. */
    fun shareCode(settings: ChannelSettings): String = ChannelSet.Builder()
        .also { wb -> wb.settings = listOf(settings) }
        .build()
        .getChannelUrl(upperCasePrefix = true, shouldAdd = true)
        .toString()

    /** Removes the secondary channel at [index]; channels after it move up one slot. */
    suspend fun removeChannel(index: Int) {
        if (index <= 0) return
        val old = radioConfigRepository.channelSetFlow.first().settings
        if (index > old.lastIndex) return
        val new = old.filterIndexed { i, _ -> i != index }
        getChannelList(new, old).forEach { radioController.setLocalChannel(it) }
    }

    private suspend fun addChannel(set: ChannelSet, settings: ChannelSettings): Int {
        val index = set.settings.size
        if (index >= MAX_CHANNELS) throw RangeTestChannelException(RangeTestChannelException.Reason.NO_FREE_SLOT)
        getChannelList(set.settings + settings, set.settings).forEach { radioController.setLocalChannel(it) }
        return index
    }

    private fun ChannelSet.toOptions(): List<RangeTestChannelOption> {
        val lora = lora_config ?: ModelChannel.default.loraConfig
        return settings.mapIndexed { index, s ->
            RangeTestChannelOption(
                index = index,
                name = ModelChannel(s, lora).name,
                isPrimary = index == 0,
                settings = s,
            )
        }
    }

    companion object {
        const val DEFAULT_NAME = "rangeTest"
        private const val MAX_CHANNELS = 8
    }
}

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

    /**
     * The index of the channel named [name], creating it with a fresh random key in a free slot when missing. Extra
     * slots with the same name (left over from earlier tests) are removed so only one remains.
     */
    suspend fun ensureChannel(name: String = DEFAULT_NAME): Int {
        val old = currentSettings()
        if (old.firstOrNull()?.name == name) return 0
        val existing = old.indices.firstOrNull { it > 0 && old[it].name == name }
        val settings =
            if (existing != null) {
                old[existing]
            } else {
                ChannelSettings.Builder()
                    .also { wb ->
                        wb.name = name
                        wb.psk = ModelChannel.getRandomKey()
                    }
                    .build()
            }
        return apply(old, withSingleChannel(old, settings))
    }

    /**
     * Joins the channel in a scanned code. A slot with the same name is overwritten with the scanned key (instead of
     * adding a second channel of that name); otherwise the channel goes into a free secondary slot. Returns its index.
     */
    suspend fun joinFromCode(code: String): Int {
        val shared =
            runCatching { CommonUri.parse(code.trim()).toChannelSet() }.getOrNull()?.settings?.firstOrNull()
                ?: throw RangeTestChannelException(RangeTestChannelException.Reason.INVALID_CODE)
        val old = currentSettings()
        return apply(old, withSingleChannel(old, shared))
    }

    /** A Meshtastic channel link holding only [settings], for the QR code the other radio scans. */
    fun shareCode(settings: ChannelSettings): String = ChannelSet.Builder()
        .also { wb -> wb.settings = listOf(settings) }
        .build()
        .getChannelUrl(upperCasePrefix = true, shouldAdd = true)
        .toString()

    /** Removes the secondary channel at [index]; channels after it move up one slot. */
    suspend fun removeChannel(index: Int) {
        val old = currentSettings()
        if (index <= 0 || index > old.lastIndex) return
        write(old, old.filterIndexed { i, _ -> i != index })
    }

    private suspend fun currentSettings(): List<ChannelSettings> = radioConfigRepository.channelSetFlow.first().settings

    /** Writes [plan] when it changes anything and returns the range-test channel's index. */
    private suspend fun apply(old: List<ChannelSettings>, plan: ChannelPlan): Int {
        if (plan.settings.size > MAX_CHANNELS) {
            throw RangeTestChannelException(RangeTestChannelException.Reason.NO_FREE_SLOT)
        }
        if (plan.settings != old) write(old, plan.settings)
        return plan.index
    }

    /**
     * Sends only the changed slots to the radio, then replaces the cached channel list. The cache has to be replaced
     * explicitly: a single-slot write never shrinks it (a disabled slot is skipped), which is what left a removed
     * channel on screen and let it be "found" again.
     */
    private suspend fun write(old: List<ChannelSettings>, new: List<ChannelSettings>) {
        getChannelList(new, old).sortedBy { it.index }.forEach { radioController.setLocalChannel(it) }
        radioConfigRepository.replaceAllSettings(new)
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

        /**
         * [old] with exactly one channel named like [settings]: the first slot of that name takes [settings] (or it is
         * appended when there is none) and later slots of the same name are dropped. The primary slot is never touched.
         */
        internal fun withSingleChannel(old: List<ChannelSettings>, settings: ChannelSettings): ChannelPlan {
            val result = mutableListOf<ChannelSettings>()
            var index = -1
            old.forEachIndexed { i, slot ->
                when {
                    i == 0 || slot.name != settings.name -> result += slot

                    index < 0 -> {
                        index = result.size
                        result += settings
                    }
                    // A later slot with the same name is a leftover duplicate: dropped.
                }
            }
            if (index < 0) {
                index = result.size
                result += settings
            }
            return ChannelPlan(result, index)
        }
    }
}

/** The channel list to write and where the range-test channel sits in it. */
internal data class ChannelPlan(val settings: List<ChannelSettings>, val index: Int)

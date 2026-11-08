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
package org.meshtastic.app.clone

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.meshtastic.app.BuildConfig
import org.meshtastic.core.common.AppInstance
import java.io.File

/** One clone the user has created. [slot] picks its process, activity and service (`:clone<slot>`). */
@Serializable
data class CloneProfile(
    val slot: Int,
    val name: String,
    /** ARGB tint for the default Meshtastic logo, and the clone's accent in Recents. */
    val color: Long,
    /** True when the user picked their own picture, stored at [CloneRegistry.customIconFile]. */
    val hasCustomIcon: Boolean = false,
    val createdAtMillis: Long = 0L,
)

/**
 * The list of clones, kept as one small JSON file in the main app's own files dir so every process (the main app and
 * each clone) reads the same list. Only the main app's clone manager writes it; writes replace the file atomically.
 */
object CloneRegistry {
    /** How many clone slots the manifest declares; set by `CLONE_SLOTS` in `config.properties`. */
    val maxSlots: Int
        get() = BuildConfig.CLONE_SLOTS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    /** The main app's files dir, never a clone's redirected one. */
    private fun baseDir(context: Context): File = File(context.dataDir, "${AppInstance.CLONES_DIR}/registry")

    private fun registryFile(context: Context) = File(baseDir(context), "clones.json")

    fun customIconFile(context: Context, slot: Int): File = File(baseDir(context), "icon_$slot.png")

    fun read(context: Context): List<CloneProfile> = synchronized(lock) {
        val file = registryFile(context)
        if (!file.exists()) return emptyList()
        runCatching { json.decodeFromString<List<CloneProfile>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun find(context: Context, slot: Int): CloneProfile? = read(context).firstOrNull { it.slot == slot }

    /** Lowest slot not taken by an existing clone, or null when every declared slot is used. */
    fun nextFreeSlot(context: Context): Int? {
        val used = read(context).map { it.slot }.toSet()
        return (1..maxSlots).firstOrNull { it !in used }
    }

    fun write(context: Context, profiles: List<CloneProfile>) = synchronized(lock) {
        val dir = baseDir(context).apply { mkdirs() }
        val tmp = File(dir, "clones.json.tmp")
        tmp.writeText(json.encodeToString(profiles.sortedBy { it.slot }))
        if (!tmp.renameTo(registryFile(context))) {
            registryFile(context).writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun upsert(context: Context, profile: CloneProfile) = synchronized(lock) {
        write(context, read(context).filterNot { it.slot == profile.slot } + profile)
    }

    fun remove(context: Context, slot: Int) = synchronized(lock) {
        write(context, read(context).filterNot { it.slot == slot })
        customIconFile(context, slot).delete()
    }
}

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

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.pm.ShortcutManagerCompat
import co.touchlab.kermit.Logger
import org.meshtastic.core.common.AppInstance
import java.io.File

/** Starts, stops, pins and deletes clones. Every call comes from the main app; a clone never manages clones. */
object CloneLauncher {
    private const val PACKAGE_PREFIX = "org.meshtastic.app.clone"

    fun shortcutId(slot: Int) = "clone_$slot"

    private fun activityName(slot: Int) = "$PACKAGE_PREFIX.CloneActivity$slot"

    private fun serviceName(slot: Int) = "$PACKAGE_PREFIX.CloneMeshService$slot"

    private fun processName(context: Context, slot: Int) =
        "${context.packageName}${AppInstance.PROCESS_SUFFIX_PREFIX}$slot"

    /**
     * The intent that opens a clone. Each clone's activity declares its own task affinity and `singleTask`, so NEW_TASK
     * is enough to give it its own entry in Recents and to bring an already running one forward.
     */
    fun launchIntent(context: Context, slot: Int): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, activityName(slot))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun launch(context: Context, slot: Int) {
        runCatching { context.startActivity(launchIntent(context, slot)) }
            .onFailure { Logger.w(it) { "Could not open clone $slot" } }
    }

    /** True while the clone's process is alive. */
    fun isRunning(context: Context, slot: Int): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val name = processName(context, slot)
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    /** Asks the launcher to pin a shortcut for [profile]; false when the launcher can't pin shortcuts. */
    fun requestPinShortcut(context: Context, profile: CloneProfile): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, CloneIcons.shortcut(context, profile), null)
    }

    /** Refreshes the label and icon of any pinned or dynamic shortcut for [profile]. */
    fun updateShortcut(context: Context, profile: CloneProfile) {
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(CloneIcons.shortcut(context, profile))) }
            .onFailure { Logger.w(it) { "Could not update the shortcut for clone ${profile.slot}" } }
    }

    /** Stops the clone's service and kills its process. Killing a same-uid process is allowed. */
    fun stop(context: Context, slot: Int) {
        runCatching {
            context.stopService(Intent().setComponent(ComponentName(context.packageName, serviceName(slot))))
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val name = processName(context, slot)
        am.runningAppProcesses?.filter { it.processName == name }?.forEach { Process.killProcess(it.pid) }
    }

    /** Stops the clone, drops its shortcuts and erases every file it owns, then removes it from the registry. */
    fun delete(context: Context, slot: Int) {
        stop(context, slot)
        val id = shortcutId(slot)
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), null)
        }
        AppInstance.slotRoot(context, slot).deleteRecursively()
        AppInstance.slotCacheRoot(context, slot).deleteRecursively()
        val prefsPrefix = AppInstance.sharedPrefsName(slot, "")
        File(context.dataDir, "shared_prefs")
            .listFiles { file -> file.name.startsWith(prefsPrefix) }
            ?.forEach {
                it.delete()
            }
        CloneRegistry.remove(context, slot)
    }
}

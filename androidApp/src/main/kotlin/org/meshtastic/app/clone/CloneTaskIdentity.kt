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

import android.app.Activity
import android.app.ActivityManager
import co.touchlab.kermit.Logger
import org.meshtastic.core.common.AppInstance

private const val TASK_ICON_PX = 192

/** Names a clone's Recents card after the clone and gives it the clone's icon. */
@Suppress("TooGenericExceptionCaught")
fun Activity.applyCloneTaskIdentity() {
    val profile = CloneRegistry.find(this, AppInstance.slot) ?: return
    try {
        val icon = CloneIcons.render(this, profile, TASK_ICON_PX)
        // The Builder (API 33) only takes a drawable resource id; a rendered bitmap still needs this constructor.
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(profile.name, icon, profile.color.toInt()))
    } catch (e: Exception) {
        Logger.w(e) { "Could not set the clone's task description" }
    }
}

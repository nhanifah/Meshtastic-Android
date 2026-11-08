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
package org.meshtastic.core.common

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppInstanceTest {

    private val pkg = "com.geeksville.mesh"

    @Test
    fun `main and unrelated processes are slot 0`() {
        assertEquals(0, AppInstance.slotForProcess(pkg, pkg))
        assertEquals(0, AppInstance.slotForProcess(null, pkg))
        assertEquals(0, AppInstance.slotForProcess("$pkg:remote", pkg))
        assertEquals(0, AppInstance.slotForProcess("other.app:clone2", pkg))
        assertEquals(0, AppInstance.slotForProcess("$pkg:clone0", pkg))
        assertEquals(0, AppInstance.slotForProcess("$pkg:cloneX", pkg))
    }

    @Test
    fun `clone processes map to their slot`() {
        assertEquals(1, AppInstance.slotForProcess("$pkg:clone1", pkg))
        assertEquals(12, AppInstance.slotForProcess("$pkg:clone12", pkg))
    }

    @Test
    fun `storage context keeps a clone's files under its own folder`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = InstanceStorageContext(app, 3)
        val root = AppInstance.slotRoot(app, 3)

        assertTrue(storage.filesDir.startsWith(root))
        assertTrue(storage.getDatabasePath("meshtastic_database_default").startsWith(File(root, "databases")))
        assertTrue(storage.cacheDir.startsWith(AppInstance.slotCacheRoot(app, 3)))
        assertEquals(storage, storage.applicationContext)
        assertEquals("clone3_mesh-prefs", AppInstance.sharedPrefsName(3, "mesh-prefs"))
        assertEquals("mesh-prefs", AppInstance.sharedPrefsName(0, "mesh-prefs"))
    }
}

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

import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Which copy of the app this process is. Slot 0 is the normal app; slots 1..N are clones, each running in its own
 * `:cloneN` process with its own radio connection, database, preferences and caches, so one phone can drive several
 * radios side by side.
 */
object AppInstance {
    /** Suffix of a clone's process name; `com.geeksville.mesh:clone3` is slot 3. */
    const val PROCESS_SUFFIX_PREFIX = ":clone"

    /** Folder under the app's data dir holding every clone's private files, one subfolder per slot. */
    const val CLONES_DIR = "clones"

    /** This process's slot; 0 for the main app. */
    var slot: Int = 0
        private set

    val isClone: Boolean
        get() = slot > 0

    /** The clone's display name, set by the app at startup so lower modules can label notifications; null in slot 0. */
    @Volatile var label: String? = null

    private var storageContext: Context? = null

    /**
     * Where this instance keeps its files. The main app gets the Application itself, so its existing data stays where
     * it is; a clone gets an [InstanceStorageContext] rooted in `clones/<slot>/`.
     */
    val storage: Context
        get() = storageContext ?: ContextServices.app

    /** Reads the slot from the process name. Call once, first thing in `Application.onCreate`. */
    fun init(app: Application) {
        slot = slotForProcess(currentProcessName(app), app.packageName)
        storageContext = if (slot > 0) InstanceStorageContext(app, slot) else app
    }

    /**
     * Android installs a package's content providers only in the process they are declared for, which is the main
     * process here. Several libraries initialize through one: Compose resources and MapLibre take their Context from a
     * provider, and androidx.startup's provider runs Kable (BLE), lifecycle and emoji init. A clone therefore attaches
     * local instances of those providers itself, before anything reads a resource or opens a radio. They are never
     * registered with the system, so their authorities still resolve to the main process.
     */
    fun attachMainProcessProviders(app: Application) {
        if (!isClone) return
        @Suppress("DEPRECATION")
        val providers =
            app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        providers
            .filter { it.processName == app.packageName }
            .forEach { info ->
                @Suppress("TooGenericExceptionCaught")
                try {
                    val provider = Class.forName(info.name).getDeclaredConstructor().newInstance() as ContentProvider
                    provider.attachInfo(app, info)
                } catch (e: Exception) {
                    Log.w("AppInstance", "Clone $slot could not attach provider ${info.name}", e)
                }
            }
    }

    /** Parses `<package>:cloneN` into N; anything else, the main process included, is slot 0. */
    fun slotForProcess(processName: String?, packageName: String): Int {
        val suffix = processName?.removePrefix(packageName)?.takeIf { it != processName } ?: return 0
        if (!suffix.startsWith(PROCESS_SUFFIX_PREFIX)) return 0
        return suffix.removePrefix(PROCESS_SUFFIX_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    /** Root of a slot's private files; valid from any process, so the main app can wipe a deleted clone. */
    fun slotRoot(context: Context, slot: Int): File = File(context.dataDir, "$CLONES_DIR/$slot")

    /** Root of a slot's cache files. */
    fun slotCacheRoot(context: Context, slot: Int): File = File(context.cacheDir, "$CLONES_DIR/$slot")

    /** SharedPreferences file name a slot uses for [name]. */
    fun sharedPrefsName(slot: Int, name: String): String = if (slot > 0) "clone${slot}_$name" else name

    private fun currentProcessName(app: Application): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }
}

/** The context to build any on-disk store from, so a clone never opens the main app's files. */
fun Context.instanceStorage(): Context = if (AppInstance.isClone) AppInstance.storage else this

/**
 * Redirects files, caches, databases and SharedPreferences into a clone's own folder. Only used to build on-disk
 * stores; everything else keeps the real Application context.
 */
class InstanceStorageContext(base: Context, private val slot: Int) : ContextWrapper(base) {
    private val root = AppInstance.slotRoot(base, slot)

    private fun dir(file: File): File = file.apply { mkdirs() }

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = dir(File(root, "files"))

    override fun getNoBackupFilesDir(): File = dir(File(root, "no_backup"))

    override fun getCacheDir(): File = dir(AppInstance.slotCacheRoot(baseContext, slot))

    override fun getDatabasePath(name: String): File = File(dir(File(root, "databases")), name)

    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    override fun databaseList(): Array<String> = File(root, "databases").list() ?: emptyArray()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(AppInstance.sharedPrefsName(slot, name), mode)

    override fun deleteSharedPreferences(name: String): Boolean =
        super.deleteSharedPreferences(AppInstance.sharedPrefsName(slot, name))
}

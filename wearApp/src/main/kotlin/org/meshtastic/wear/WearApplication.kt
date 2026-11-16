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
package org.meshtastic.wear

import android.app.Application
import androidx.work.Configuration
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.plugin.module.dsl.startKoin
import org.meshtastic.core.common.AppInstance
import org.meshtastic.core.common.ContextServices
import org.meshtastic.wear.di.WearKoinApp
import org.meshtastic.wear.surface.WearSurfaceUpdater

/** Starts the shared mesh graph on the watch. */
class WearApplication :
    Application(),
    Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        Logger.setMinSeverity(if (BuildConfig.DEBUG) Severity.Debug else Severity.Info)
        ContextServices.app = this
        AppInstance.init(this)
        startKoin<WearKoinApp> {
            androidContext(this@WearApplication)
            workManagerFactory()
        }
        GlobalContext.get().get<WearSurfaceUpdater>().start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KoinWorkerFactory()).build()
}

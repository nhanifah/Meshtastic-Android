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
package org.meshtastic.wear.di

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.ble.di.CoreBleAndroidModule
import org.meshtastic.core.ble.di.CoreBleModule
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.di.CoreCommonModule
import org.meshtastic.core.common.di.GOOGLE_SERVICES_AVAILABLE
import org.meshtastic.core.common.di.PROCESS_LIFECYCLE
import org.meshtastic.core.data.di.CoreDataAndroidModule
import org.meshtastic.core.data.di.CoreDataModule
import org.meshtastic.core.database.di.CoreDatabaseAndroidModule
import org.meshtastic.core.database.di.CoreDatabaseModule
import org.meshtastic.core.datastore.di.CoreDatastoreAndroidModule
import org.meshtastic.core.datastore.di.CoreDatastoreModule
import org.meshtastic.core.network.HttpClientDefaults
import org.meshtastic.core.network.configureDefaultRetry
import org.meshtastic.core.network.di.CoreNetworkAndroidModule
import org.meshtastic.core.network.di.CoreNetworkModule
import org.meshtastic.core.network.repository.ProbeTableProvider
import org.meshtastic.core.network.service.ApiService
import org.meshtastic.core.network.service.ApiServiceImpl
import org.meshtastic.core.prefs.di.CorePrefsAndroidModule
import org.meshtastic.core.prefs.di.CorePrefsModule
import org.meshtastic.core.service.di.CoreServiceAndroidModule
import org.meshtastic.core.service.di.CoreServiceModule
import org.meshtastic.feature.rangetest.di.FeatureRangeTestModule
import org.meshtastic.wear.BuildConfig

@Module(
    includes =
    [
        org.meshtastic.core.di.di.CoreDiModule::class,
        CoreCommonModule::class,
        CoreBleModule::class,
        CoreBleAndroidModule::class,
        CoreDataModule::class,
        CoreDataAndroidModule::class,
        org.meshtastic.core.domain.di.CoreDomainModule::class,
        CoreDatabaseModule::class,
        CoreDatabaseAndroidModule::class,
        org.meshtastic.core.repository.di.CoreRepositoryModule::class,
        CoreDatastoreModule::class,
        CoreDatastoreAndroidModule::class,
        CorePrefsModule::class,
        CorePrefsAndroidModule::class,
        CoreServiceModule::class,
        CoreServiceAndroidModule::class,
        CoreNetworkModule::class,
        CoreNetworkAndroidModule::class,
        org.meshtastic.core.takserver.di.CoreTakServerModule::class,
        FeatureRangeTestModule::class,
    ],
)
@ComponentScan("org.meshtastic.wear")
class WearKoinModule {
    @Single
    @Named(PROCESS_LIFECYCLE)
    fun provideProcessLifecycle(): Lifecycle = ProcessLifecycleOwner.get().lifecycle

    @Single
    fun provideBuildConfigProvider(): BuildConfigProvider = object : BuildConfigProvider {
        override val isDebug: Boolean = BuildConfig.DEBUG
        override val applicationId: String = BuildConfig.APPLICATION_ID
        override val versionCode: Int = BuildConfig.VERSION_CODE
        override val versionName: String = BuildConfig.VERSION_NAME
        override val absoluteMinFwVersion: String = BuildConfig.ABS_MIN_FW_VERSION
        override val minFwVersion: String = BuildConfig.MIN_FW_VERSION
    }

    @Suppress("FunctionOnlyReturningConstant")
    @Single
    @Named(GOOGLE_SERVICES_AVAILABLE)
    fun googleServicesAvailable(): Boolean = true

    @Single fun provideWorkManager(context: Application): WorkManager = WorkManager.getInstance(context)

    /** Watches have no USB host; the serial transport stays unavailable. */
    @Single
    fun provideUsbManager(application: Application): UsbManager? =
        application.getSystemService(Context.USB_SERVICE) as UsbManager?

    @Single fun provideProbeTable(provider: ProbeTableProvider): ProbeTable = provider.get()

    @Single fun provideUsbSerialProber(probeTable: ProbeTable): UsbSerialProber = UsbSerialProber(probeTable)

    @Single fun bindApiService(apiServiceImpl: ApiServiceImpl): ApiService = apiServiceImpl

    @Single
    fun provideConnectivityManager(application: Application): ConnectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Single
    fun provideNsdManager(application: Application): NsdManager =
        application.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Single
    fun provideHttpClient(json: Json, buildConfigProvider: BuildConfigProvider): HttpClient =
        HttpClient(engineFactory = OkHttp) {
            install(plugin = ContentNegotiation) { json(json) }
            install(DefaultRequest) {
                url(HttpClientDefaults.API_BASE_URL)
                header(
                    HttpHeaders.UserAgent,
                    "Meshtastic-WearOS/${buildConfigProvider.versionName} (${buildConfigProvider.applicationId})",
                )
            }
            install(plugin = HttpTimeout) {
                requestTimeoutMillis = HttpClientDefaults.REQUEST_TIMEOUT_MS
                connectTimeoutMillis = HttpClientDefaults.TIMEOUT_MS
                socketTimeoutMillis = HttpClientDefaults.TIMEOUT_MS
            }
            install(plugin = HttpRequestRetry) { configureDefaultRetry() }
        }
}

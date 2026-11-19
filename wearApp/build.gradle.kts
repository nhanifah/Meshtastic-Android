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

import com.android.build.api.dsl.ApplicationExtension
import org.meshtastic.buildlogic.configProperties
import org.meshtastic.buildlogic.resolveVersionInfo
import java.io.File
import java.util.Properties

/*
 * Meshtastic for Wear OS (built for the Galaxy Watch 8 Classic, Wear OS 6). It runs the same mesh stack as the phone
 * app and reaches the radio either through the paired phone (Wear Data Layer, see PhoneRelayProtocol) or directly
 * over Bluetooth. It shares the phone app's application id and signing key, which the Data Layer requires.
 */

val versionInfo = resolveVersionInfo()

plugins {
    alias(libs.plugins.meshtastic.android.application)
    alias(libs.plugins.meshtastic.android.application.compose)
    alias(libs.plugins.meshtastic.kotlinx.serialization)
    alias(libs.plugins.meshtastic.koin)
}

val keystorePropertiesFile = isolated.rootProject.projectDirectory.file("keystore.properties").asFile
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

configure<ApplicationExtension> {
    namespace = "org.meshtastic.wear"

    signingConfigs {
        getByName("debug") {
            storeFile = isolated.rootProject.projectDirectory.file("config/debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            // Same keystore as the phone app (the Data Layer only pairs apps with the same signature). A relative
            // path in keystore.properties is relative to :androidApp, as the phone build reads it.
            storeFile =
                (keystoreProperties["storeFile"] as String?)?.let { path ->
                    File(path).takeIf { it.isAbsolute }
                        ?: isolated.rootProject.projectDirectory.dir("androidApp").file(path).asFile
                }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    defaultConfig {
        applicationId = configProperties.getProperty("APPLICATION_ID")
        // Wear OS 3 and later; the Galaxy Watch 8 Classic ships Wear OS 6 (API 36).
        minSdk = 30
        versionCode = versionInfo.versionCode
        versionName = "${versionInfo.versionName} (${versionInfo.versionCode}) wear"
        buildConfigField("String", "MIN_FW_VERSION", "\"${versionInfo.minFwVersion}\"")
        buildConfigField("String", "ABS_MIN_FW_VERSION", "\"${versionInfo.absMinFwVersion}\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            if (keystoreProperties["storeFile"] != null) {
                signingConfig = signingConfigs.named("release").get()
            }
            isDebuggable = false
        }
    }

    packaging { jniLibs { keepDebugSymbols.add("**/*.so") } }
}

androidComponents {
    // Pairs with the phone app's google debug build over the Data Layer, which matches on application id.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("${configProperties.getProperty("APPLICATION_ID")}.google.debug")
    }
}

dependencies {
    implementation(projects.core.ble)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.di)
    implementation(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.prefs)
    implementation(projects.core.repository)
    implementation(projects.core.resources)
    implementation(projects.core.service)
    implementation(projects.core.takserver)
    implementation(projects.core.ui)
    implementation(projects.feature.rangetest)
    implementation(libs.meshtastic.protobufs)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.compose.multiplatform.ui)
    implementation(libs.compose.multiplatform.ui.tooling.preview)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.kermit)
    implementation(libs.usb.serial.android)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test-junit"))
    testRuntimeOnly(libs.junit.vintage.engine)
}

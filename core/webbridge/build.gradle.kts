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

plugins {
    alias(libs.plugins.meshtastic.kmp.library)
    alias(libs.plugins.meshtastic.kmp.jvm.android)
    alias(libs.plugins.meshtastic.koin)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.repository)
            implementation(projects.core.common)
            implementation(projects.core.di)
            implementation(projects.core.resources)
            implementation(projects.core.model)
            implementation(libs.meshtastic.protobufs)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kermit)
        }

        getByName("jvmAndroidMain") {
            dependencies {
                // HTTP runs on CIO bound to loopback; TLS is terminated in front of it (see WebBridgeServer), which
                // keeps Netty out of the APK. BouncyCastle issues the per-install self-signed certificate.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.bouncycastle.bcpkix)
            }
        }

        commonTest.dependencies { implementation(projects.core.testing) }

        jvmTest.dependencies { implementation(libs.ktor.server.test.host) }
    }
}

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
import com.android.build.api.dsl.LibraryExtension

// Ships only libcodec2jni.so for each ABI. The Kotlin API lives in :core:codec2; this is a separate plain Android
// library because the KMP Android library plugin has no externalNativeBuild.
plugins { alias(libs.plugins.meshtastic.android.library) }

configure<LibraryExtension> {
    namespace = "org.meshtastic.core.codec2.jni"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }

    externalNativeBuild {
        cmake {
            path = file("../codec2/native/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

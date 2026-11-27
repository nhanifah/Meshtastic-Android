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
}

kotlin {
    sourceSets {
        commonMain.dependencies { implementation(libs.kermit) }
        androidMain.dependencies { implementation(projects.core.codec2Jni) }
    }
}

// Desktop: build libcodec2jni for the host with CMake and bundle it as a jar resource under
// codec2/<os>-<arch>/. Other desktop OSes come from CI runners of that OS. Without CMake the
// library is skipped with a warning and Codec2.isAvailable reports false at runtime.
val codec2NativeSrc = layout.projectDirectory.dir("native")
val codec2HostBuild = layout.buildDirectory.dir("codec2-host")
val codec2HostResources = layout.buildDirectory.dir("generated/codec2-resources")

val cmakeExecutable: String =
    providers.gradleProperty("codec2.cmake").orNull
        ?: listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map { File(it, "cmake/4.1.2/bin/cmake") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
        ?: (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .map { File(it, "cmake") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
        ?: "cmake"
val cmakeFound = File(cmakeExecutable).canExecute()

val hostPlatformId: String = run {
    val os = System.getProperty("os.name").lowercase()
    val osId =
        when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    val arch =
        when (val a = System.getProperty("os.arch").lowercase()) {
            "amd64",
            "x86_64",
            -> "x86_64"

            "aarch64",
            "arm64",
            -> "aarch64"

            else -> a
        }
    "$osId-$arch"
}

val configureCodec2Desktop by
    tasks.registering(Exec::class) {
        enabled = cmakeFound
        inputs.dir(codec2NativeSrc)
        outputs.dir(codec2HostBuild)
        executable = cmakeExecutable
        args(
            "-S",
            codec2NativeSrc.asFile.absolutePath,
            "-B",
            codec2HostBuild.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DJNI_INCLUDE_ROOT=${File(System.getProperty("java.home"), "include").absolutePath}",
        )
    }

val buildCodec2Desktop by
    tasks.registering(Exec::class) {
        dependsOn(configureCodec2Desktop)
        enabled = cmakeFound
        inputs.dir(codec2NativeSrc)
        outputs.dir(codec2HostBuild)
        executable = cmakeExecutable
        args("--build", codec2HostBuild.get().asFile.absolutePath, "--config", "Release", "--target", "codec2jni")
    }

val bundleCodec2Desktop by
    tasks.registering(Sync::class) {
        dependsOn(buildCodec2Desktop)
        if (!cmakeFound) {
            logger.warn("core:codec2: CMake not found; the desktop build will ship without Codec2 (voice disabled)")
        }
        from(codec2HostBuild) {
            include("libcodec2jni.dylib", "libcodec2jni.so", "codec2jni.dll", "Release/codec2jni.dll")
            eachFile { path = name }
        }
        includeEmptyDirs = false
        into(codec2HostResources.map { it.dir("codec2/$hostPlatformId") })
    }

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(codec2HostResources)
}

tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(bundleCodec2Desktop) }

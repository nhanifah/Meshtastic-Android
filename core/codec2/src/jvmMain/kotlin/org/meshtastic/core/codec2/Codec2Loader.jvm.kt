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
package org.meshtastic.core.codec2

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop: the library is a jar resource at `codec2/<os>-<arch>/<file>` (built by :core:codec2's `buildCodec2Desktop`
 * task). The JVM can only load a library from a real file, so it is copied to a temp file first.
 */
internal actual fun loadCodec2Library() {
    val resource = "/codec2/${desktopPlatformId()}/${System.mapLibraryName("codec2jni")}"
    val stream =
        Codec2Native::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError("No Codec2 library bundled for ${desktopPlatformId()} ($resource)")
    val dir = Files.createTempDirectory("meshtastic-codec2").toFile().apply { deleteOnExit() }
    val file = File(dir, System.mapLibraryName("codec2jni")).apply { deleteOnExit() }
    stream.use { Files.copy(it, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    System.load(file.absolutePath)
}

/** `macos-aarch64`, `linux-x86_64`, `windows-x86_64`, … — must match the Gradle task's output folder. */
internal fun desktopPlatformId(): String {
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
    return "$osId-$arch"
}

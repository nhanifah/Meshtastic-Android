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
package org.meshtastic.core.webbridge

/**
 * Serves the web client straight out of the `build.tar` the meshtastic/web project publishes for radios to host.
 *
 * Every file in that archive is pre-gzipped (`index.html.gz`), so each one is served under its plain name with
 * `Content-Encoding: gzip` and is never decompressed on the phone. Only regular files are read. Directory entries,
 * links and anything with a `..` segment are skipped.
 */
class TarWebClientAssets private constructor(private val files: Map<String, WebAsset>) : WebClientAssets {

    override fun open(path: String): WebAsset? = files[path]

    /** Number of servable files, for diagnostics. */
    val size: Int
        get() = files.size

    companion object {
        private const val BLOCK = 512
        private const val NAME_END = 100
        private const val SIZE_START = 124
        private const val SIZE_END = 136
        private const val TYPE_FLAG = 156
        private const val PREFIX_START = 345
        private const val PREFIX_END = 500
        private const val OCTAL_RADIX = 8
        private const val GZIP_SUFFIX = ".gz"

        /** Parses [tar] (POSIX ustar). Throws [IllegalArgumentException] when the archive is truncated or malformed. */
        fun parse(tar: ByteArray): TarWebClientAssets {
            val files = HashMap<String, WebAsset>()
            var offset = 0
            while (offset + BLOCK <= tar.size) {
                if (tar.isZeroBlock(offset)) break
                val prefix = tar.field(offset + PREFIX_START, offset + PREFIX_END)
                val baseName = tar.field(offset, offset + NAME_END)
                val name = if (prefix.isEmpty()) baseName else "$prefix/$baseName"
                val size =
                    tar.field(offset + SIZE_START, offset + SIZE_END)
                        .trim()
                        .ifEmpty { "0" }
                        .toLongOrNull(OCTAL_RADIX)
                        ?.toInt() ?: throw IllegalArgumentException("Bad tar size field")
                val type = tar[offset + TYPE_FLAG].toInt().toChar()
                val dataStart = offset + BLOCK
                require(size >= 0 && dataStart + size <= tar.size) { "Truncated tar archive" }
                if ((type == '0' || type == '\u0000') && size > 0) {
                    addFile(files, name, tar.copyOfRange(dataStart, dataStart + size))
                }
                offset = dataStart + (size + BLOCK - 1) / BLOCK * BLOCK
            }
            return TarWebClientAssets(files)
        }

        private fun addFile(files: MutableMap<String, WebAsset>, rawName: String, bytes: ByteArray) {
            val path = rawName.removePrefix("./").trimStart('/')
            if (path.isEmpty() || path.split('/').any { it == ".." || it.isEmpty() }) return
            val gzipped = path.endsWith(GZIP_SUFFIX)
            val servedPath = if (gzipped) path.removeSuffix(GZIP_SUFFIX) else path
            val contentEncoding = if (gzipped) "gzip" else null
            files[servedPath] = WebAsset(bytes, WebClientAssets.contentTypeFor(servedPath), contentEncoding)
        }

        private fun ByteArray.isZeroBlock(offset: Int): Boolean =
            (offset until offset + BLOCK).all { this[it] == 0.toByte() }

        /** A NUL-terminated ASCII header field. */
        private fun ByteArray.field(start: Int, end: Int): String {
            var stop = start
            while (stop < end && this[stop] != 0.toByte()) stop++
            return copyOfRange(start, stop).decodeToString()
        }
    }
}

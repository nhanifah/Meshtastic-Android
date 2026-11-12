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
package org.meshtastic.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import co.touchlab.kermit.Logger
import org.meshtastic.core.common.log.InMemoryLogBuffer
import java.awt.Window
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Replaces Compose Desktop's default window exception handler, which shows a bare "Error / Unknown error" box when the
 * exception has no message and then closes the window (here: hides it to the tray, so a running range test seems to
 * vanish).
 *
 * This one writes a crash report — the full stack trace plus the recent in-memory log — to
 * `<home>/.meshtastic/logs/crash-<time>.txt`, logs it, tells the user the exception type and where the report is, and
 * keeps the window open. Work running outside the UI (beacon sending, radio connection) is not affected either way.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal object DesktopCrashReporter : WindowExceptionHandlerFactory {
    private const val MAX_LOG_CHARS = 200_000
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    override fun exceptionHandler(window: Window): WindowExceptionHandler = object : WindowExceptionHandler {
        override fun onException(throwable: Throwable) {
            val report = runCatching { writeReport(throwable) }.getOrNull()
            Logger.e(throwable) { "Uncaught UI exception; report: ${report?.absolutePath ?: "not written"}" }
            SwingUtilities.invokeLater { showDialog(window, throwable, report) }
        }
    }

    /** Where crash reports go; also handy for support. */
    val logDirectory: File
        get() = File(System.getProperty("user.home"), ".meshtastic/logs")

    private fun writeReport(throwable: Throwable): File {
        val dir = logDirectory.apply { mkdirs() }
        val file = File(dir, "crash-${LocalDateTime.now().format(stamp)}.txt")
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val log = InMemoryLogBuffer.snapshot().takeLast(MAX_LOG_CHARS)
        file.writeText(
            buildString {
                appendLine("Meshtastic Desktop ${DesktopBuildConfig.VERSION_NAME} (${DesktopBuildConfig.VERSION_CODE})")
                appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("Java: ${System.getProperty("java.version")}")
                appendLine("Render API: ${System.getProperty("skiko.renderApi") ?: "default"}")
                appendLine()
                appendLine(trace)
                appendLine("---- recent log ----")
                appendLine(log)
            },
        )
        return file
    }

    private fun showDialog(window: Window, throwable: Throwable, report: File?) {
        val what = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Exception"
        val message = buildString {
            appendLine("Something went wrong in the window, but the app keeps running.")
            appendLine()
            appendLine(what)
            throwable.message?.let { appendLine(it) }
            throwable.stackTrace.firstOrNull()?.let { appendLine("at $it") }
            appendLine()
            if (report != null) append("Details were saved to:\n${report.absolutePath}") else append("No report.")
        }
        JOptionPane.showMessageDialog(
            window.takeIf { it.isDisplayable },
            message,
            "Meshtastic",
            JOptionPane.WARNING_MESSAGE,
        )
    }
}

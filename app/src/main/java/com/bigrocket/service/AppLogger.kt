package com.bigrocket.service

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App-wide diagnostic log. Off by default (see [LogSettingsStore]) - [log] is a cheap no-op
 * until the user turns it on from the menu, so call sites can log freely without worrying about
 * overhead on the normal, logging-off path.
 *
 * Kept intentionally simple: an in-memory ring buffer for immediate access, mirrored to a single
 * capped file (so a log survives a VPN-service process restart within the same session) that the
 * user can export in full via "Save As" from the menu.
 */
object AppLogger {
    private const val MAX_MEMORY_LINES = 5_000
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024 // 2MB
    private const val LOG_FILE_NAME = "bigrocket_log.txt"

    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val memoryBuffer = ArrayDeque<String>()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var enabled: Boolean = false
    @Volatile private var logFile: File? = null
    @Volatile private var initialized: Boolean = false

    /** Safe to call repeatedly (e.g. from both MainActivity and BigRocketVpnService's onCreate)
     *  - only does real work once. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        enabled = LogSettingsStore.isEnabled(appContext)
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        LogSettingsStore.setEnabled(context, value)
        enabled = value
        // Always record the on/off transition itself - useful context for whoever reads the
        // exported log later, and confirms the toggle actually took effect.
        log("Logging", if (value) "logging enabled" else "logging disabled")
    }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${timestampFormat.format(java.util.Date())} [$tag] $message"
        synchronized(memoryBuffer) {
            memoryBuffer.addLast(line)
            while (memoryBuffer.size > MAX_MEMORY_LINES) memoryBuffer.removeFirst()
        }
        logFile?.let { file ->
            writeExecutor.execute {
                runCatching {
                    file.appendText(line + "\n")
                    rotateIfNeeded(file)
                }
            }
        }
    }

    /** Convenience for logging a caught exception without every call site formatting it by hand. */
    fun logError(tag: String, message: String, error: Throwable) {
        log(tag, "$message: ${error.javaClass.simpleName}: ${error.message}")
    }

    /** Full exported text - reads the persisted file when one exists (covers everything since
     *  logging was turned on, including across a process restart), falling back to the
     *  in-memory buffer only if the file is missing/unreadable for some reason. Also appends
     *  the existing Aether-side [studio.cluvex.aether.core.DiagnosticsLog] (tunnel engine
     *  switches, crash-survival lines - already being recorded unconditionally today,
     *  independent of this class's own on/off switch) so a single export has both. */
    fun exportText(): String {
        val ownLog = run {
            val file = logFile
            if (file != null && file.exists()) {
                val fromFile = runCatching { file.readText() }.getOrNull()
                if (!fromFile.isNullOrEmpty()) return@run fromFile
            }
            synchronized(memoryBuffer) { memoryBuffer.joinToString("\n") }
        }
        val tunnelLog = runCatching { studio.cluvex.aether.core.DiagnosticsLog.exportText() }.getOrDefault("")
        return buildString {
            append("== BigRocket log ==\n")
            append(ownLog)
            if (tunnelLog.isNotBlank()) {
                append("\n\n== Aether/tunnel diagnostics ==\n")
                append(tunnelLog)
            }
        }
    }

    /** Drops the oldest half of the file once it crosses [MAX_FILE_BYTES], instead of letting a
     *  long-running session grow the log file without bound. Runs on [writeExecutor], never on
     *  a caller's thread. */
    private fun rotateIfNeeded(file: File) {
        if (file.length() <= MAX_FILE_BYTES) return
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        val trimmed = lines.drop(lines.size / 2)
        runCatching { file.writeText(trimmed.joinToString("\n") + "\n") }
    }
}

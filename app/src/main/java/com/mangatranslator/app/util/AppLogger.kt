package com.mangatranslator.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Simple rolling debug log saved to app-private storage. The Settings screen
 * shows this so the user can copy exactly what happened during a translate
 * attempt - no computer, no adb, no logcat required.
 *
 * Kept separate from [CrashHandler] (which is only for hard crashes) so this
 * can log normal step-by-step progress too: "button tapped", "captured frame",
 * "OCR found N blocks", "translation returned empty", etc.
 *
 * Performance: every write is queued onto a single background thread and is
 * APPENDED to the file. The previous implementation read the whole file,
 * concatenated, and rewrote it on every call - synchronously, on the caller's
 * thread (usually the UI thread) and several times per text block - which
 * added a very noticeable delay to every translation.
 */
object AppLogger {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_CHARS = 60_000 // keep the file from growing forever
    private const val TRIM_CHECK_EVERY = 50

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLogger").apply { isDaemon = true }
    }

    // Only ever touched from the single logger thread above (SimpleDateFormat isn't thread-safe).
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var writesSinceTrimCheck = 0

    fun log(context: Context, tag: String, message: String) {
        Log.d(tag, message)
        enqueue(context, "D", tag, message)
    }

    fun error(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val full = if (throwable != null) "$message :: $throwable" else message
        enqueue(context, "E", tag, full)
    }

    private fun enqueue(context: Context, level: String, tag: String, message: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        try {
            executor.execute { write(appContext, now, level, tag, message) }
        } catch (_: Exception) {
            // Logging must never itself crash the app.
        }
    }

    private fun write(context: Context, timestamp: Long, level: String, tag: String, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("${timeFormat.format(Date(timestamp))} $level/$tag: $message\n")

            writesSinceTrimCheck++
            if (writesSinceTrimCheck >= TRIM_CHECK_EVERY) {
                writesSinceTrimCheck = 0
                // Persian text takes 2+ bytes per char, hence the generous byte threshold.
                if (file.length() > MAX_CHARS * 3L) {
                    file.writeText(file.readText().takeLast(MAX_CHARS))
                }
            }
        } catch (_: Exception) {
            // Logging must never itself crash the app.
        }
    }

    /** Waits (briefly) for queued writes so a read right after a log call sees them. */
    private fun flush() {
        try {
            executor.submit {}.get(500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    fun readLog(context: Context): String {
        flush()
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists() && file.length() > 0) file.readText().takeLast(MAX_CHARS) else "هنوز چیزی ثبت نشده است."
    }

    fun clearLog(context: Context) {
        val appContext = context.applicationContext
        try {
            executor.execute { File(appContext.filesDir, FILE_NAME).delete() }
        } catch (_: Exception) {
        }
        flush()
    }
}

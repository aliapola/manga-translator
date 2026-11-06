package com.mangatranslator.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple rolling debug log saved to app-private storage. The Settings screen
 * shows this so the user can copy exactly what happened during a translate
 * attempt - no computer, no adb, no logcat required.
 *
 * Kept separate from [CrashHandler] (which is only for hard crashes) so this
 * can log normal step-by-step progress too: "button tapped", "captured frame",
 * "OCR found N blocks", "translation returned empty", etc.
 */
object AppLogger {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_CHARS = 60_000 // keep the file from growing forever

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, tag: String, message: String) {
        Log.d(tag, message)
        append(context, "D", tag, message)
    }

    fun error(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val full = if (throwable != null) "$message :: $throwable" else message
        append(context, "E", tag, full)
    }

    private fun append(context: Context, level: String, tag: String, message: String) {
        try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val line = "${timeFormat.format(Date())} $level/$tag: $message\n"
            val existing = if (file.exists()) file.readText() else ""
            val combined = (existing + line).takeLast(MAX_CHARS)
            file.writeText(combined)
        } catch (_: Exception) {
            // Logging must never itself crash the app.
        }
    }

    fun readLog(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists() && file.length() > 0) file.readText() else "هنوز چیزی ثبت نشده است."
    }

    fun clearLog(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}

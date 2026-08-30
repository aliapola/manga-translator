package com.mangatranslator.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Since debugging over adb/logcat isn't available to every user, this saves
 * the last crash (or any manually-caught exception) to a plain text file in
 * app-private storage. The Settings screen can then show it so the user can
 * copy/paste it without any PC or developer tools.
 */
object CrashHandler {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveToFile(appContext, "Uncaught", throwable)
            } catch (_: Exception) {
                // If we can't even save the crash, there's nothing more to do here.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Call this from inside a try/catch to record a handled exception too. */
    fun recordManual(context: Context, label: String, throwable: Throwable) {
        try {
            saveToFile(context.applicationContext, label, throwable)
        } catch (_: Exception) {
        }
    }

    private fun saveToFile(context: Context, label: String, throwable: Throwable) {
        val writer = StringWriter()
        writer.write("[$label]\n")
        throwable.printStackTrace(PrintWriter(writer))
        File(context.filesDir, FILE_NAME).writeText(writer.toString())
    }

    fun readLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun clearLastCrash(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}

package com.mangatranslator.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mangatranslator.app.data.AppDatabase
import com.mangatranslator.app.util.CrashHandler

/**
 * Application entry point.
 * Holds the single Room database instance and creates the notification
 * channels required by the two foreground services (capture + overlay).
 */
class MyApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    companion object {
        const val CHANNEL_CAPTURE = "capture_channel"
        const val CHANNEL_OVERLAY = "overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val capture = NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val overlay = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(capture)
            manager.createNotificationChannel(overlay)
        }
    }
}

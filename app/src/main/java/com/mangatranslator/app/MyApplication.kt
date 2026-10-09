package com.mangatranslator.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mangatranslator.app.data.SettingsStore
import com.mangatranslator.app.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point.
 * Creates the notification channels required by the two foreground services
 * (capture + overlay).
 */
class MyApplication : Application() {

    companion object {
        const val CHANNEL_CAPTURE = "capture_channel"
        const val CHANNEL_OVERLAY = "overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        createNotificationChannels()
        cleanUpRemovedFeatures()
    }

    /**
     * One-off cleanup for data left behind by features that no longer exist:
     * the custom-dictionary database and the stored Azure API key/region.
     * Both calls are no-ops when there is nothing to remove.
     */
    private fun cleanUpRemovedFeatures() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deleteDatabase("manga_translator.db")
                SettingsStore.purgeLegacyKeys(this@MyApplication)
            } catch (_: Exception) {
                // Best-effort cleanup only.
            }
        }
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

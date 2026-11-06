package com.mangatranslator.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mangatranslator.app.MyApplication
import com.mangatranslator.app.R
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.CrashHandler
import kotlinx.coroutines.delay

/**
 * Foreground service that holds the MediaProjection instance for the
 * lifetime of the "translate mode" session and grabs single still frames
 * on demand (one frame per OCR request - we do NOT keep a continuous
 * decode running, to keep battery usage reasonable).
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var density = 0

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            CrashHandler.recordManual(this, "ScreenCaptureService.startForeground", e)
            AppLogger.error(this, TAG, "startForeground failed", e)
            Toast.makeText(this, "خطا در شروع سرویس ضبط صفحه: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED) ?: Activity_RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (data != null && resultCode != Activity_RESULT_CANCELED) {
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                // Android 14+ requires a callback to be registered before createVirtualDisplay()
                // is called, otherwise it throws "Must register a callback before starting".
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLogger.log(this@ScreenCaptureService, TAG, "MediaProjection.onStop callback fired")
                        virtualDisplay?.release()
                        imageReader?.close()
                        virtualDisplay = null
                        imageReader = null
                    }
                }, null)
                setupVirtualDisplay()
                AppLogger.log(this, TAG, "Virtual display ready: ${width}x${height} @ ${density}dpi")
            } catch (e: Exception) {
                CrashHandler.recordManual(this, "ScreenCaptureService.setupVirtualDisplay", e)
                AppLogger.error(this, TAG, "setupVirtualDisplay failed", e)
                Toast.makeText(this, "خطا در راه‌اندازی ضبط صفحه: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            AppLogger.error(this, TAG, "onStartCommand received without a valid MediaProjection result (resultCode=$resultCode, data=$data)")
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MangaTranslatorCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    /**
     * Called by the system when screen orientation (or size, e.g. entering
     * split-screen) changes. The VirtualDisplay/ImageReader were sized for
     * the old dimensions, so captureFrame() would silently keep returning
     * frames at the wrong resolution (or none at all) after a rotation
     * unless we rebuild them here.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mediaProjection == null) return
        AppLogger.log(this, TAG, "onConfigurationChanged: rebuilding virtual display")
        try {
            virtualDisplay?.release()
            imageReader?.close()
            setupVirtualDisplay()
            AppLogger.log(this, TAG, "Virtual display rebuilt after rotation: ${width}x${height}")
        } catch (e: Exception) {
            CrashHandler.recordManual(this, "ScreenCaptureService.onConfigurationChanged", e)
            AppLogger.error(this, TAG, "Failed to rebuild virtual display after rotation", e)
        }
    }

    /**
     * Grabs exactly one frame from the current screen. Retries a few times
     * with a short delay: right after the virtual display is created (or
     * right after the user taps the floating button), a new frame may not
     * have been produced yet, so a single acquireLatestImage() attempt can
     * come back empty even though everything is working correctly.
     */
    suspend fun captureFrame(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            AppLogger.error(this, TAG, "captureFrame: imageReader is null - virtual display not set up yet")
            return null
        }

        repeat(MAX_CAPTURE_ATTEMPTS) { attempt ->
            val bitmap = tryAcquireImage(reader)
            if (bitmap != null) {
                AppLogger.log(this, TAG, "captureFrame: got a frame on attempt ${attempt + 1}/$MAX_CAPTURE_ATTEMPTS")
                return bitmap
            }
            delay(CAPTURE_RETRY_DELAY_MS)
        }
        AppLogger.error(this, TAG, "captureFrame: no frame available after $MAX_CAPTURE_ATTEMPTS attempts")
        return null
    }

    private fun tryAcquireImage(reader: ImageReader): Bitmap? {
        return try {
            val image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "captureFrame: exception while reading image buffer", e)
            null
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, MyApplication.CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"
        private const val MAX_CAPTURE_ATTEMPTS = 6
        private const val CAPTURE_RETRY_DELAY_MS = 120L
        // Activity.RESULT_CANCELED == 0, duplicated here to avoid an Activity import in a Service file.
        private const val Activity_RESULT_CANCELED = 0
    }
}

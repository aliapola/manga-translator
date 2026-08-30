package com.mangatranslator.app.service

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.mangatranslator.app.MyApplication
import com.mangatranslator.app.R
import com.mangatranslator.app.data.SettingsStore
import com.mangatranslator.app.ocr.OcrProcessor
import com.mangatranslator.app.ocr.OcrScript
import com.mangatranslator.app.translation.LanguagePackManager
import com.mangatranslator.app.translation.TranslationManager
import com.mangatranslator.app.translation.TranslationSource
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Some window-manager operations can legitimately race with the service
 * being torn down (e.g. an in-flight online-translation request finishes
 * right as the user stops translation, or Android kills the service for
 * memory). These wrappers turn that harmless race into a no-op log line
 * instead of a crash.
 */
private fun WindowManager.safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
    try {
        if (view.isAttachedToWindow) updateViewLayout(view, params)
    } catch (e: Exception) {
        AppLogger.log(view.context, "OverlayService", "safeUpdateViewLayout: view already detached, ignoring (${e.message})")
    }
}

private fun WindowManager.safeRemoveView(view: View) {
    try {
        if (view.isAttachedToWindow) removeView(view)
    } catch (e: Exception) {
        AppLogger.log(view.context, "OverlayService", "safeRemoveView: view already detached, ignoring (${e.message})")
    }
}

/**
 * Owns:
 *  - the floating draggable button shown over every app
 *  - the "result overlay": a full-screen static image (the captured
 *    screenshot with translated text drawn directly onto it at each
 *    bubble's exact position)
 *
 * Talks to [ScreenCaptureService] (bound) to grab a frame, then runs
 * OCR + translation and renders the result.
 *
 * IMPORTANT positioning note: earlier versions positioned individual
 * transparent TextViews inside the overlay window using OCR pixel
 * coordinates as margins. That depends on the overlay window's own
 * coordinate space exactly matching the captured screenshot's coordinate
 * space - on some devices/OEM ROMs the system applies extra insets/limits
 * to SYSTEM_ALERT_WINDOW-type windows that aren't fully predictable from
 * app code, which caused translations to render off-position no matter how
 * precisely the window's flags/size were tuned. Drawing the text directly
 * onto a copy of the screenshot bitmap sidesteps that entirely: the text
 * and the bubble it belongs to are now permanently part of the same image,
 * so they can never drift apart - only the whole image as a unit could
 * shift, which is not what users were reporting.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: ImageView? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var resultContainer: FrameLayout? = null
    private var resultParams: WindowManager.LayoutParams? = null

    private var captureService: ScreenCaptureService? = null
    private var bound = false

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val ocrProcessor = OcrProcessor()
    private lateinit var translationManager: TranslationManager

    // Defaults to English source -> Persian target; actual value is re-read
    // from SettingsStore before every translation (see refreshSettings()).
    var sourceScript: OcrScript = OcrScript.LATIN
    var sourceLangCode: String = TranslateLanguage.ENGLISH
    val targetLangCode: String = TranslateLanguage.PERSIAN

    private var overlayOpacity: Float = SettingsStore.DEFAULT_OVERLAY_OPACITY
    private var buttonSizeDp: Int = SettingsStore.DEFAULT_BUTTON_SIZE_DP

    private fun ocrScriptForLangCode(code: String): OcrScript = when (code) {
        TranslateLanguage.JAPANESE -> OcrScript.JAPANESE
        TranslateLanguage.KOREAN -> OcrScript.KOREAN
        TranslateLanguage.CHINESE -> OcrScript.CHINESE
        else -> OcrScript.LATIN
    }

    /** Re-reads Settings so changes (source language, button size, opacity, online provider) take effect immediately. */
    private suspend fun refreshSettings() {
        val code = SettingsStore.sourceLangFlow(applicationContext).first()
        if (code != sourceLangCode) {
            AppLogger.log(this, TAG, "Source language changed: $sourceLangCode -> $code")
        }
        sourceLangCode = code
        sourceScript = ocrScriptForLangCode(code)

        overlayOpacity = SettingsStore.overlayOpacityFlow(applicationContext).first()

        val newSize = SettingsStore.buttonSizeDpFlow(applicationContext).first()
        if (newSize != buttonSizeDp) {
            buttonSizeDp = newSize
            applyButtonSize()
        }

        val onlineProvider = SettingsStore.onlineProviderFlow(applicationContext).first()
        val azureKey = SettingsStore.azureApiKeyFlow(applicationContext).first()
        val azureRegion = SettingsStore.azureRegionFlow(applicationContext).first()
        val myMemoryEmail = SettingsStore.myMemoryEmailFlow(applicationContext).first()
        translationManager.updateOnlineConfig(onlineProvider, azureKey, azureRegion, myMemoryEmail)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureService = (service as ScreenCaptureService.LocalBinder).getService()
            bound = true
            AppLogger.log(this@OverlayService, TAG, "Bound to ScreenCaptureService")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            captureService = null
            AppLogger.log(this@OverlayService, TAG, "Disconnected from ScreenCaptureService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val app = application as MyApplication
            translationManager = TranslationManager(applicationContext, app.database.dictionaryDao(), LanguagePackManager())

            bindService(Intent(this, ScreenCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            addFloatingButton()
            addResultContainer()
            startIdleReleaseLoop()
            observeAutoTranslateSetting()

            scope.launch { refreshSettings() }

            AppLogger.log(this, TAG, "OverlayService.onCreate finished successfully")
        } catch (e: Exception) {
            CrashHandler.recordManual(this, "OverlayService.onCreate", e)
            AppLogger.error(this, TAG, "onCreate failed", e)
            Toast.makeText(this, "خطا در راه‌اندازی دکمه شناور: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            CrashHandler.recordManual(this, "OverlayService.onStartCommand", e)
            AppLogger.error(this, TAG, "startForeground failed", e)
            Toast.makeText(this, "خطا در شروع سرویس Overlay: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_SOURCE_LANG)?.let { sourceLangCode = it }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Periodically frees OCR/translation model memory after a few minutes of no activity. */
    private fun startIdleReleaseLoop() {
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                ocrProcessor.releaseIfIdle(IDLE_RELEASE_THRESHOLD_MS)
                translationManager.releaseIfIdle(IDLE_RELEASE_THRESHOLD_MS)
            }
        }
    }

    // ---------- Auto-translate on page change ----------

    private var autoTranslateJob: Job? = null
    private var lastFrameSignature: Long? = null

    private fun observeAutoTranslateSetting() {
        scope.launch {
            SettingsStore.autoTranslateFlow(applicationContext).collect { enabled ->
                if (enabled) startAutoTranslateLoop() else stopAutoTranslateLoop()
            }
        }
    }

    private fun startAutoTranslateLoop() {
        if (autoTranslateJob?.isActive == true) return
        AppLogger.log(this, TAG, "Auto-translate loop started")
        lastFrameSignature = null
        autoTranslateJob = scope.launch {
            while (isActive) {
                delay(AUTO_TRANSLATE_POLL_INTERVAL_MS)
                if (!bound || captureService == null) continue
                val bitmap = captureService?.captureFrame() ?: continue
                val signature = frameSignature(bitmap)
                val previous = lastFrameSignature
                lastFrameSignature = signature
                if (previous != null && kotlin.math.abs(signature - previous) > FRAME_CHANGE_THRESHOLD) {
                    AppLogger.log(this@OverlayService, TAG, "Auto-translate: page change detected")
                    runTranslationCycle(bitmap, region = null, silent = true)
                }
            }
        }
    }

    private fun stopAutoTranslateLoop() {
        if (autoTranslateJob == null) return
        autoTranslateJob?.cancel()
        autoTranslateJob = null
        lastFrameSignature = null
        AppLogger.log(this, TAG, "Auto-translate loop stopped")
    }

    private fun frameSignature(bitmap: Bitmap): Long {
        var sum = 0L
        val gridSize = 16
        for (i in 0 until gridSize) {
            for (j in 0 until gridSize) {
                val x = (bitmap.width * i / gridSize).coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height * j / gridSize).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                sum += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
            }
        }
        return sum
    }

    // ---------- Floating button ----------

    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun realScreenSizePx(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun applyButtonSize() {
        val button = floatingButton ?: return
        val params = floatingButtonParams ?: return
        val sizePx = dpToPx(buttonSizeDp)
        params.width = sizePx
        params.height = sizePx
        windowManager.safeUpdateViewLayout(button, params)
    }

    private fun addFloatingButton() {
        val sizePx = dpToPx(buttonSizeDp)
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setBackgroundResource(R.drawable.bg_floating_button)
            val pad = (sizePx * 0.28f).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDrag = false

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX)
                    val dy = (event.rawY - initialTouchY)
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) isDrag = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.safeUpdateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        AppLogger.log(this@OverlayService, TAG, "Floating button tapped")
                        onFloatingButtonTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        floatingButton = button
        floatingButtonParams = params
    }

    // ---------- Result overlay (annotated screenshot) ----------

    private fun addResultContainer() {
        val container = FrameLayout(this)
        val (screenWidthPx, screenHeightPx) = realScreenSizePx()
        val params = WindowManager.LayoutParams(
            screenWidthPx,
            screenHeightPx,
            overlayWindowType(),
            touchFlagsForState(hasResults = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        container.setOnClickListener { dismissResults() }
        windowManager.addView(container, params)
        resultContainer = container
        resultParams = params
    }

    private fun touchFlagsForState(hasResults: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (hasResults) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun setResultsTouchable(touchable: Boolean) {
        val container = resultContainer ?: return
        val params = resultParams ?: return
        params.flags = touchFlagsForState(hasResults = touchable)
        windowManager.safeUpdateViewLayout(container, params)
    }

    /** One-tap translate: capture -> OCR -> translate -> render. */
    private fun onFloatingButtonTapped() {
        scope.launch {
            floatingButton?.alpha = 0.4f
            dismissResults()
            refreshSettings()
            AppLogger.log(this@OverlayService, TAG, "Using source language: $sourceLangCode (script=$sourceScript)")

            if (!bound || captureService == null) {
                AppLogger.error(this@OverlayService, TAG, "Tap ignored: not yet bound to ScreenCaptureService")
                toast("در حال آماده‌سازی سرویس ضبط صفحه هستیم، چند ثانیه دیگر دوباره امتحان کنید")
                floatingButton?.alpha = 1f
                return@launch
            }

            val bitmap = captureService?.captureFrame()
            if (bitmap == null) {
                AppLogger.error(this@OverlayService, TAG, "Tap failed: captureFrame() returned null")
                toast("نتوانستیم از صفحه عکس بگیریم. دوباره تلاش کنید")
                floatingButton?.alpha = 1f
                return@launch
            }

            runTranslationCycle(bitmap, region = null, silent = false)
            floatingButton?.alpha = 1f
        }
    }

    /** Runs a single translate pass restricted to a user-picked screen region. */
    fun translateRegion(region: Rect) {
        scope.launch {
            dismissResults()
            refreshSettings()
            val bitmap = captureService?.captureFrame()
            if (bitmap == null) {
                AppLogger.error(this@OverlayService, TAG, "translateRegion: captureFrame() returned null")
                toast("نتوانستیم از صفحه عکس بگیریم")
                return@launch
            }
            runTranslationCycle(bitmap, region = region, silent = false)
        }
    }

    /**
     * Shared OCR -> translate -> render pipeline used by manual taps,
     * region-select, and the auto-translate loop. When [silent] is true
     * (background auto-translate), no Toast feedback is shown.
     */
    private suspend fun runTranslationCycle(bitmap: Bitmap, region: Rect?, silent: Boolean) {
        val blocks = try {
            ocrProcessor.recognize(bitmap, sourceScript, region)
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "OCR failed", e)
            if (!silent) toast("خطا در تشخیص متن (OCR): ${e.message}")
            return
        }
        AppLogger.log(this, TAG, "OCR found ${blocks.size} text block(s)")

        if (blocks.isEmpty()) {
            if (!silent) toast("هیچ متنی روی صفحه شناسایی نشد")
            return
        }

        // Translate all blocks CONCURRENTLY rather than one at a time. This
        // matters most when an online provider is slow/unreachable: with a
        // sequential loop, a page with 10+ text blocks could wait out a full
        // network timeout for EVERY block in turn (a minute or more); running
        // them in parallel means the whole page waits out roughly one
        // timeout's worth of time regardless of how many blocks there are.
        val translationResults = coroutineScope {
            blocks.map { block ->
                async {
                    val result = try {
                        translationManager.translate(block.text, sourceLangCode, targetLangCode)
                    } catch (e: Exception) {
                        AppLogger.error(this@OverlayService, TAG, "translate() threw for block '${block.text}'", e)
                        null
                    }
                    block to result
                }
            }.awaitAll()
        }

        val translations = mutableListOf<Pair<Rect, String>>()
        var missingPack = false
        for ((block, result) in translationResults) {
            if (result == null) continue
            AppLogger.log(
                this, TAG,
                "block='${block.text}' box=${block.boundingBox} -> '${result.translatedText}' source=${result.source}"
            )
            if (result.source == TranslationSource.NONE) missingPack = true
            if (result.translatedText.isNotBlank()) {
                translations += block.boundingBox to result.translatedText
            }
        }

        if (translations.isNotEmpty()) {
            if (!scope.isActive) {
                AppLogger.log(this, TAG, "runTranslationCycle: service is shutting down, skipping render")
                return
            }
            renderAnnotatedScreenshot(bitmap, translations)
            setResultsTouchable(true)
        } else if (!silent) {
            if (missingPack) {
                toast("متن پیدا شد ولی ترجمه‌ای موجود نیست — بسته زبان را از «بسته‌های زبان آفلاین» دانلود کنید یا ترجمه آنلاین را در تنظیمات فعال کنید")
            } else {
                toast("متن پیدا شد ولی ترجمه‌ای برگردانده نشد")
            }
        }
    }

    /**
     * Draws every translated string directly onto a copy of the captured
     * screenshot, at the exact bounding box OCR reported, then shows that
     * single annotated image full-screen. Because the text is baked into
     * the same bitmap the coordinates came from, it is mathematically
     * guaranteed to land on the right spot - there is no separate
     * "overlay window coordinate system" left to get out of sync.
     */
    private fun renderAnnotatedScreenshot(source: Bitmap, translations: List<Pair<Rect, String>>) {
        val container = resultContainer ?: return
        val annotated = try {
            buildAnnotatedBitmap(source, translations)
        } catch (e: Exception) {
            CrashHandler.recordManual(this, "renderAnnotatedScreenshot", e)
            AppLogger.error(this, TAG, "Failed to build annotated screenshot", e)
            toast("خطا در رسم ترجمه روی تصویر: ${e.message}")
            return
        }

        val imageView = ImageView(this).apply {
            setImageBitmap(annotated)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = true
            setOnClickListener { dismissResults() }
        }
        val (screenWidthPx, screenHeightPx) = realScreenSizePx()
        val lp = FrameLayout.LayoutParams(screenWidthPx, screenHeightPx)
        container.addView(imageView, lp)
    }

    private fun buildAnnotatedBitmap(source: Bitmap, translations: List<Pair<Rect, String>>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val alphaByte = (overlayOpacity.coerceIn(0.3f, 1f) * 255).toInt()
        val backgroundPaint = Paint().apply {
            color = Color.argb(alphaByte, 15, 15, 15)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for ((box, text) in translations) {
            val safeBox = Rect(
                box.left.coerceIn(0, result.width),
                box.top.coerceIn(0, result.height),
                box.right.coerceIn(0, result.width),
                box.bottom.coerceIn(0, result.height)
            )
            if (safeBox.width() <= 0 || safeBox.height() <= 0) continue
            canvas.drawRect(safeBox, backgroundPaint)
            drawTextInBox(canvas, text, safeBox)
        }
        return result
    }

    /** Draws [text] centered inside [box], shrinking the font until it fits (Persian RTL-aware). */
    private fun drawTextInBox(canvas: Canvas, text: String, box: Rect) {
        val padding = 8
        val availableWidth = (box.width() - padding * 2).coerceAtLeast(20)
        val availableHeight = (box.height() - padding * 2).coerceAtLeast(16)

        val textPaint = TextPaint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }

        var textSizePx = 40f
        var layout = buildStaticLayout(text, textPaint, textSizePx, availableWidth)
        while (layout.height > availableHeight && textSizePx > 10f) {
            textSizePx -= 2f
            layout = buildStaticLayout(text, textPaint, textSizePx, availableWidth)
        }

        val verticalOffset = ((availableHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(
            (box.left + padding).toFloat(),
            box.top + padding + verticalOffset
        )
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, textSizePx: Float, width: Int): StaticLayout {
        paint.textSize = textSizePx
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    /** Clears the annotated screenshot AND lets touches pass back through to the app underneath. */
    fun dismissResults() {
        resultContainer?.removeAllViews()
        setResultsTouchable(false)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, MyApplication.CHANNEL_OVERLAY)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        floatingButton?.let { windowManager.safeRemoveView(it) }
        resultContainer?.let { windowManager.safeRemoveView(it) }
        if (bound) unbindService(connection)
        ocrProcessor.close()
        translationManager.close()
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "OverlayService"
        private const val IDLE_CHECK_INTERVAL_MS = 60_000L
        private const val IDLE_RELEASE_THRESHOLD_MS = 3 * 60_000L
        private const val AUTO_TRANSLATE_POLL_INTERVAL_MS = 1_500L
        private const val FRAME_CHANGE_THRESHOLD = 12_000L
    }
}

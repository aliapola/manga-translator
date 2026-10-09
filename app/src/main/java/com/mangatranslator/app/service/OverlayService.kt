package com.mangatranslator.app.service

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
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
import com.mangatranslator.app.ocr.RecognizedBlock
import com.mangatranslator.app.translation.LanguagePackManager
import com.mangatranslator.app.translation.TranslationManager
import com.mangatranslator.app.translation.TranslationResult
import com.mangatranslator.app.translation.TranslationSource
import com.mangatranslator.app.util.AppLogger
import com.mangatranslator.app.util.CrashHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *  - the "result overlay": a full-screen frozen screenshot of the page with
 *    a [TranslationLayerView] on top that draws each translation at its
 *    bubble's exact position
 *
 * Talks to [ScreenCaptureService] (bound) to grab a frame, then runs
 * OCR + translation and renders the result.
 *
 * Speed design:
 *  - capture / OCR / translation / text layout all run on background threads;
 *    the UI thread only attaches views and draws.
 *  - all text blocks are translated in parallel (identical texts only once)
 *    and each translation is drawn the moment it is ready, instead of
 *    waiting for the slowest block.
 *  - OCR and the offline model are warmed up when the service starts.
 *  - nothing is copied or re-encoded: the captured bitmap is shown directly.
 *
 * Positioning note: the screenshot ImageView and the translation layer are
 * the same size and use the same screenshot->view scale (FIT_XY), so the
 * text and the bubble it belongs to can never drift apart, regardless of
 * how the OEM treats overlay-window insets.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: ImageView? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var resultContainer: FrameLayout? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var resultLayer: TranslationLayerView? = null
    private var hasResults = false

    private var captureService: ScreenCaptureService? = null
    private var bound = false

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    /** The single in-flight capture->OCR->translate->render pass (manual, region or auto). */
    private var cycleJob: Job? = null

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

    /** Re-reads Settings (one DataStore access) so changes take effect immediately. */
    private suspend fun refreshSettings() {
        val settings = SettingsStore.snapshot(applicationContext)
        if (settings.sourceLang != sourceLangCode) {
            AppLogger.log(this, TAG, "Source language changed: $sourceLangCode -> ${settings.sourceLang}")
        }
        sourceLangCode = settings.sourceLang
        sourceScript = ocrScriptForLangCode(settings.sourceLang)
        overlayOpacity = settings.overlayOpacity

        if (settings.buttonSizeDp != buttonSizeDp) {
            buttonSizeDp = settings.buttonSizeDp
            applyButtonSize()
        }

        translationManager.updateOnlineConfig(
            settings.onlineProvider, settings.myMemoryEmail, settings.geminiApiKey, settings.geminiModel
        )
    }

    /** Loads the OCR model (and the offline translator, when it's the primary path) before the first tap. */
    private suspend fun warmUp() {
        val script = sourceScript
        val source = sourceLangCode
        val target = targetLangCode
        coroutineScope {
            launch(Dispatchers.Default) { ocrProcessor.warmUp(script) }
            launch(Dispatchers.Default) { translationManager.warmUp(source, target) }
        }
        AppLogger.log(this@OverlayService, TAG, "Warm-up finished (script=$script)")
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
            translationManager = TranslationManager(applicationContext, LanguagePackManager())

            bindService(Intent(this, ScreenCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            addFloatingButton()
            addResultContainer()
            startIdleReleaseLoop()
            observeAutoTranslateSetting()

            scope.launch {
                refreshSettings()
                warmUp()
            }

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
                // While a translation is running or its result is on screen there is
                // nothing to detect (the screen is frozen) - skip the capture entirely.
                if (hasResults || cycleJob?.isActive == true) continue
                val bitmap = captureService?.captureFrame() ?: continue
                val signature = withContext(Dispatchers.Default) { frameSignature(bitmap) }
                val previous = lastFrameSignature
                lastFrameSignature = signature
                if (previous != null && kotlin.math.abs(signature - previous) > FRAME_CHANGE_THRESHOLD) {
                    AppLogger.log(this@OverlayService, TAG, "Auto-translate: page change detected")
                    launchCycle {
                        refreshSettings()
                        runTranslationCycle(bitmap, region = null, silent = true)
                    }
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

    /** Cancels any in-flight pass and starts a new one. */
    private fun launchCycle(block: suspend CoroutineScope.() -> Unit) {
        cycleJob?.cancel()
        cycleJob = scope.launch(block = block)
    }

    /** One-tap translate: capture -> OCR -> translate -> render. */
    private fun onFloatingButtonTapped() {
        launchCycle {
            // The alpha change also makes the display produce a fresh frame,
            // which the (static-screen) capture below relies on.
            floatingButton?.alpha = 0.4f
            try {
                val hadResults = hasResults
                clearOverlay()
                refreshSettings()
                AppLogger.log(this@OverlayService, TAG, "Using source language: $sourceLangCode (script=$sourceScript)")

                if (!bound || captureService == null) {
                    AppLogger.error(this@OverlayService, TAG, "Tap ignored: not yet bound to ScreenCaptureService")
                    toast("در حال آماده‌سازی سرویس ضبط صفحه هستیم، چند ثانیه دیگر دوباره امتحان کنید")
                    return@launchCycle
                }

                // If our own overlay was just removed, give the display a moment to
                // compose a frame without it so it doesn't end up in the screenshot.
                if (hadResults) delay(OVERLAY_REMOVAL_SETTLE_MS)

                val startedAt = System.currentTimeMillis()
                val bitmap = captureService?.captureFrame()
                if (bitmap == null) {
                    AppLogger.error(this@OverlayService, TAG, "Tap failed: captureFrame() returned null")
                    toast("نتوانستیم از صفحه عکس بگیریم. دوباره تلاش کنید")
                    return@launchCycle
                }
                AppLogger.log(this@OverlayService, TAG, "Timing: capture=${System.currentTimeMillis() - startedAt}ms")

                runTranslationCycle(bitmap, region = null, silent = false)
            } finally {
                floatingButton?.alpha = 1f
            }
        }
    }

    /** Runs a single translate pass restricted to a user-picked screen region. */
    fun translateRegion(region: Rect) {
        launchCycle {
            clearOverlay()
            refreshSettings()
            val bitmap = captureService?.captureFrame()
            if (bitmap == null) {
                AppLogger.error(this@OverlayService, TAG, "translateRegion: captureFrame() returned null")
                toast("نتوانستیم از صفحه عکس بگیریم")
                return@launchCycle
            }
            runTranslationCycle(bitmap, region = region, silent = false)
        }
    }

    private class BlockOutcome(
        val block: RecognizedBlock,
        val result: TranslationResult?,
        val item: TranslationLayerView.Item?
    )

    /**
     * Shared OCR -> translate -> render pipeline used by manual taps,
     * region-select, and the auto-translate loop. When [silent] is true
     * (background auto-translate), no Toast feedback is shown.
     *
     * Every distinct text is translated in parallel on a background thread;
     * as soon as one finishes (and its text layout is computed) it is sent to
     * the UI thread and drawn, so the first bubbles appear long before the
     * slowest one is done.
     */
    private suspend fun runTranslationCycle(bitmap: Bitmap, region: Rect?, silent: Boolean) {
        val script = sourceScript
        val sourceLang = sourceLangCode
        val targetLang = targetLangCode

        val ocrStart = System.currentTimeMillis()
        val blocks = try {
            withContext(Dispatchers.Default) { ocrProcessor.recognize(bitmap, script, region) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "OCR failed", e)
            if (!silent) toast("خطا در تشخیص متن (OCR): ${e.message}")
            return
        }
        val ocrMs = System.currentTimeMillis() - ocrStart
        AppLogger.log(this, TAG, "OCR found ${blocks.size} text block(s) in ${ocrMs}ms")

        if (blocks.isEmpty()) {
            if (!silent) toast("هیچ متنی روی صفحه شناسایی نشد")
            return
        }

        val translateStart = System.currentTimeMillis()

        // Gemini mode: one request for the whole page (no-op for other providers).
        try {
            withContext(Dispatchers.Default) {
                translationManager.prefetchBatch(blocks.map { it.text }, sourceLang, targetLang)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(this, TAG, "prefetchBatch threw", e)
        }

        val outcomes = Channel<BlockOutcome>(Channel.UNLIMITED)
        var shown = 0
        var missingPack = false
        var firstShownMs = -1L

        coroutineScope {
            // Identical texts (e.g. repeated sound effects) are translated once.
            for ((text, group) in blocks.groupBy { it.text }) {
                launch(Dispatchers.Default) {
                    val result = try {
                        translationManager.translate(text, sourceLang, targetLang)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.error(this@OverlayService, TAG, "translate() threw for block '$text'", e)
                        null
                    }
                    for (block in group) {
                        val item = try {
                            if (result != null && result.translatedText.isNotBlank()) {
                                TranslationLayerView.prepare(result.translatedText, block.boundingBox, bitmap.width, bitmap.height)
                            } else null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error(this@OverlayService, TAG, "layout failed for block '$text'", e)
                            null
                        }
                        outcomes.send(BlockOutcome(block, result, item))
                    }
                }
            }

            // Runs on the UI thread: attach views / draw each result as it arrives.
            repeat(blocks.size) {
                val outcome = outcomes.receive()
                val result = outcome.result
                if (result != null) {
                    AppLogger.log(
                        this@OverlayService, TAG,
                        "block='${outcome.block.text}' -> '${result.translatedText}' source=${result.source}"
                    )
                    if (result.source == TranslationSource.NONE) missingPack = true
                }
                val item = outcome.item
                if (item != null) {
                    ensureResultViews(bitmap)?.add(item)
                    if (shown == 0) firstShownMs = System.currentTimeMillis() - translateStart
                    shown++
                }
            }
        }

        AppLogger.log(
            this, TAG,
            "Timing: ocr=${ocrMs}ms firstTranslation=${firstShownMs}ms allTranslations=${System.currentTimeMillis() - translateStart}ms shown=$shown/${blocks.size}"
        )

        if (shown == 0 && !silent) {
            if (missingPack) {
                toast("متن پیدا شد ولی ترجمه‌ای موجود نیست — بسته زبان را از «بسته‌های زبان آفلاین» دانلود کنید یا ترجمه آنلاین را در تنظیمات فعال کنید")
            } else {
                toast("متن پیدا شد ولی ترجمه‌ای برگردانده نشد")
            }
        }
    }

    /**
     * Shows the frozen screenshot (no copy - the captured bitmap itself) with
     * the translation layer on top. Created lazily when the first translation
     * is ready; later calls return the same layer.
     */
    private fun ensureResultViews(source: Bitmap): TranslationLayerView? {
        resultLayer?.let { return it }
        val container = resultContainer ?: return null

        val imageView = ImageView(this).apply {
            setImageBitmap(source)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = true
            setOnClickListener { dismissResults() }
        }
        val layer = TranslationLayerView(this, source.width, source.height, overlayOpacity)

        val (screenWidthPx, screenHeightPx) = realScreenSizePx()
        container.addView(imageView, FrameLayout.LayoutParams(screenWidthPx, screenHeightPx))
        container.addView(layer, FrameLayout.LayoutParams(screenWidthPx, screenHeightPx))

        resultLayer = layer
        hasResults = true
        setResultsTouchable(true)
        return layer
    }

    /** Removes the result views and lets touches pass back through to the app underneath. */
    private fun clearOverlay() {
        resultContainer?.removeAllViews()
        resultLayer = null
        hasResults = false
        setResultsTouchable(false)
    }

    /** Stops any in-flight translation and clears the result overlay. */
    fun dismissResults() {
        cycleJob?.cancel()
        clearOverlay()
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
        private const val IDLE_RELEASE_THRESHOLD_MS = 10 * 60_000L
        private const val AUTO_TRANSLATE_POLL_INTERVAL_MS = 1_500L
        private const val FRAME_CHANGE_THRESHOLD = 12_000L
        private const val OVERLAY_REMOVAL_SETTLE_MS = 60L
    }
}

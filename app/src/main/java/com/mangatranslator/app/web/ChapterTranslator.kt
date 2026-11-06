package com.mangatranslator.app.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.mangatranslator.app.ocr.OcrProcessor
import com.mangatranslator.app.ocr.RecognizedBlock
import com.mangatranslator.app.ocr.OcrScript
import com.mangatranslator.app.ocr.ocrScriptForLangCode
import com.mangatranslator.app.render.BubbleRenderer
import com.mangatranslator.app.translation.Lang
import com.mangatranslator.app.translation.TranslationManager
import com.mangatranslator.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

sealed class ChapterImageResult {
    data class Done(val file: File) : ChapterImageResult()
    object NoText : ChapterImageResult()
    object Failed : ChapterImageResult()
}

/**
 * یک تصویر چپتر را کامل پردازش می‌کند:
 * دانلود -> دیکد -> OCR تکه‌تکه (برای تصاویر بلند وبتون) -> ترجمه موازی ->
 * کشیدن ترجمه روی تصویر -> ذخیره JPEG در کش.
 * نتیجه روی دیسک کش می‌شود؛ بار بعد همان چپتر فوری باز می‌شود.
 */
class ChapterTranslator(private val context: Context) {

    private val ocr = OcrProcessor()
    private val translator = TranslationManager(context)
    val dir: File = File(context.cacheDir, "webreader").apply { mkdirs() }

    @Volatile private var colloquial = true

    fun setConfig(email: String, colloquial: Boolean) {
        this.colloquial = colloquial
        translator.updateConfig(email, colloquial)
    }

    fun fileFor(url: String, srcLang: String): File {
        val md = MessageDigest.getInstance("MD5").digest("$url|$srcLang|v2|c${if (colloquial) 1 else 0}".toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    suspend fun process(
        url: String,
        referer: String,
        userAgent: String,
        cookie: String?,
        srcLang: String,
        overlayOpacity: Float
    ): ChapterImageResult = withContext(Dispatchers.Default) {
        val out = fileFor(url, srcLang)
        if (out.exists() && out.length() > 0) return@withContext ChapterImageResult.Done(out)

        val bytes = ImageDownloader.download(url, referer, userAgent, cookie)
        if (bytes == null) {
            AppLogger.error(context, TAG, "download failed: $url")
            return@withContext ChapterImageResult.Failed
        }
        val bmp = decodeMutable(bytes)
        if (bmp == null) {
            AppLogger.error(context, TAG, "decode failed: $url")
            return@withContext ChapterImageResult.Failed
        }

        try {
            val blocks = ocrTiles(bmp, ocrScriptForLangCode(srcLang))
            if (blocks.isEmpty()) return@withContext ChapterImageResult.NoText

            val canvas = Canvas(bmp)
            val bg = BubbleRenderer.backgroundPaint(overlayOpacity)
            var drawn = 0
            translator.translateAll(blocks.map { it.text }, srcLang, Lang.PERSIAN)
                .collect { (i, tr) ->
                    val b = blocks[i].boundingBox
                    val box = Rect(
                        b.left.coerceIn(0, bmp.width), b.top.coerceIn(0, bmp.height),
                        b.right.coerceIn(0, bmp.width), b.bottom.coerceIn(0, bmp.height)
                    )
                    BubbleRenderer.drawTranslation(canvas, box, tr, bg)
                    drawn++
                }
            if (drawn == 0) return@withContext ChapterImageResult.Failed

            val tmp = File(dir, out.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(out)) return@withContext ChapterImageResult.Failed
            ChapterImageResult.Done(out)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.error(context, TAG, "process failed: $url", e)
            ChapterImageResult.Failed
        } finally {
            bmp.recycle()
        }
    }

    /** دیکد با سقف حافظه (حدود ۱۶ میلیون پیکسل) تا تصاویر خیلی بلند OOM نشوند. */
    private fun decodeMutable(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (decoded.isMutable) return decoded
        return decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
    }

    /**
     * OCR تکه‌تکه برای تصاویر بلند: هر تکه ۱۶۰۰ پیکسل ارتفاع با ۱۶۰ پیکسل هم‌پوشانی.
     * هر بلاک فقط در تکه‌ای نگه داشته می‌شود که مرکزش در «ناحیه مالکیت» آن تکه است،
     * پس متن‌های ناحیه هم‌پوشانی دوبار ترجمه نمی‌شوند.
     */
    private suspend fun ocrTiles(bmp: Bitmap, script: OcrScript): List<RecognizedBlock> {
        val w = bmp.width
        val h = bmp.height
        val result = mutableListOf<RecognizedBlock>()
        var y = 0
        while (y < h) {
            val bottom = minOf(y + TILE_H, h)
            val isLast = bottom >= h
            val blocks = ocr.recognize(bmp, script, Rect(0, y, w, bottom))
            val ownTop = if (y == 0) 0 else y + OVERLAP / 2
            val ownBottom = if (isLast) h else bottom - OVERLAP / 2
            blocks.filterTo(result) {
                val c = (it.boundingBox.top + it.boundingBox.bottom) / 2
                c >= ownTop && c < ownBottom
            }
            if (isLast) break
            y += TILE_H - OVERLAP
        }
        return result
    }

    /** حذف فایل‌های کش قدیمی‌تر از [maxAgeMs]. */
    fun cleanup(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun close() {
        ocr.close()
        translator.close()
    }

    companion object {
        private const val TAG = "ChapterTranslator"
        private const val TILE_H = 1600
        private const val OVERLAP = 160
        private const val MAX_PIXELS = 16_000_000L
    }
}

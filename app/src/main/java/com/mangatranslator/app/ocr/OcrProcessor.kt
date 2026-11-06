package com.mangatranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Maps a language code (en/ja/ko/zh) to the OCR model that should read it. */
fun ocrScriptForLangCode(code: String): OcrScript = when (code) {
    "ja" -> OcrScript.JAPANESE
    "ko" -> OcrScript.KOREAN
    "zh" -> OcrScript.CHINESE
    else -> OcrScript.LATIN
}

/** Which on-device OCR model to run. Manga is almost always JA, manhwa KO, manhua ZH. */
enum class OcrScript { LATIN, JAPANESE, KOREAN, CHINESE }

/**
 * A recognized text block, already mapped back to full-screen coordinates,
 * so the overlay can be drawn directly on top of the original bubble.
 */
data class RecognizedBlock(
    val text: String,
    val boundingBox: Rect,
    /** ML Kit reports blocks in normal reading order; useful for vertical JP/manga text. */
    val isLikelyVertical: Boolean
)

class OcrProcessor {

    private val recognizers: MutableMap<OcrScript, TextRecognizer> = mutableMapOf()
    private var recognizersLastUsed = System.currentTimeMillis()

    @Synchronized
    private fun recognizerFor(script: OcrScript): TextRecognizer {
        recognizersLastUsed = System.currentTimeMillis()
        return recognizers.getOrPut(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    /**
     * Runs OCR on [bitmap], optionally restricted to [region] (a user-selected
     * crop of the captured screen). [script] should match the source language
     * chosen by the user, since manga/manhwa/manhua use different CJK models.
     *
     * Mid-sentence line breaks inside a single block are flattened into
     * spaces before translation (a sentence with real line breaks in it gets
     * translated line-by-line by the offline model and comes out stilted -
     * joining it into one continuous sentence fixes that). Positioning is
     * always ML Kit's own per-block bounding box, unmodified - an earlier
     * version of this function also merged separate blocks that looked like
     * they belonged to the same bubble, but that occasionally chained
     * unrelated bubbles together into one box rendered in a single corner
     * of the screen, so that step was removed. Each block is now rendered
     * exactly where ML Kit reports it.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        script: OcrScript,
        region: Rect? = null
    ): List<RecognizedBlock> = withContext(Dispatchers.Default) {
        val cropped = region?.let {
            Bitmap.createBitmap(bitmap, it.left, it.top, it.width(), it.height())
        } ?: bitmap

        // Speed: downscale large frames before OCR (fewer pixels = faster).
        // Boxes are mapped back to the original coordinates afterwards.
        val longSide = maxOf(cropped.width, cropped.height)
        val scale = if (longSide > MAX_OCR_LONG_SIDE) MAX_OCR_LONG_SIDE.toFloat() / longSide else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else cropped
        val enhanced = enhanceContrast(scaled)

        val image = InputImage.fromBitmap(enhanced, 0)
        val result: Text = try {
            recognizerFor(script).process(image).await()
        } finally {
            // free temporary bitmaps (never the caller's original)
            if (enhanced !== scaled && enhanced !== bitmap) enhanced.recycle()
            if (scaled !== cropped && scaled !== bitmap) scaled.recycle()
            if (cropped !== bitmap) cropped.recycle()
        }

        val inv = 1f / scale
        val offsetX = region?.left ?: 0
        val offsetY = region?.top ?: 0

        result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            val cleanedText = flattenLineBreaks(block.text)
            if (cleanedText.isBlank()) return@mapNotNull null
            val shifted = Rect(
                (box.left * inv).toInt() + offsetX, (box.top * inv).toInt() + offsetY,
                (box.right * inv).toInt() + offsetX, (box.bottom * inv).toInt() + offsetY
            )
            RecognizedBlock(
                text = cleanedText,
                boundingBox = shifted,
                isLikelyVertical = looksVertical(block)
            )
        }
    }

    /** Creates the recognizer ahead of time so the first tap doesn't pay the model-load cost. */
    fun prewarm(script: OcrScript) {
        recognizerFor(script)
    }

    /** Collapses real line breaks (and the extra spaces around them) into single spaces. */
    private fun flattenLineBreaks(text: String): String =
        text.replace(Regex("\\s*\n\\s*"), " ").replace(Regex(" {2,}"), " ").trim()

    /**
     * Heuristic: manga speech-bubble text in JP/KO/ZH is often set vertically,
     * right-to-left column by column. ML Kit doesn't expose orientation
     * directly, so we infer it from the block's aspect ratio and per-line
     * bounding boxes (tall/narrow lines stacked horizontally = vertical text).
     */
    private fun looksVertical(block: Text.TextBlock): Boolean {
        val box = block.boundingBox ?: return false
        val linesAreNarrowColumns = block.lines.size > 1 && block.lines.all { line ->
            val lb = line.boundingBox ?: return@all false
            lb.height() > lb.width() * 1.3
        }
        val blockIsTaller = box.height() > box.width()
        return linesAreNarrowColumns && blockIsTaller
    }

    /**
     * Bumps contrast and saturation slightly before OCR. Manga/manhwa/manhua
     * fonts are often stylized (thin strokes, screentone backgrounds, low
     * contrast against busy art) which trips up the recognizer more than
     * plain document text does - a mild contrast boost measurably helps
     * without distorting the image enough to hurt normal-contrast pages.
     */
    private fun enhanceContrast(source: Bitmap): Bitmap {
        return try {
            val contrast = 1.25f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val colorMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
            canvas.drawBitmap(source, 0f, 0f, paint)
            output
        } catch (e: Exception) {
            // If anything goes wrong, fall back to the original image rather than failing OCR entirely.
            source
        }
    }

    /** Releases OCR model instances if none have been used recently, to free memory while idle. */
    @Synchronized
    fun releaseIfIdle(idleMillis: Long) {
        if (recognizers.isNotEmpty() && System.currentTimeMillis() - recognizersLastUsed > idleMillis) {
            recognizers.values.forEach { it.close() }
            recognizers.clear()
        }
    }

    @Synchronized
    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    companion object {
        /** Longest side (px) the frame is shrunk to before OCR. */
        private const val MAX_OCR_LONG_SIDE = 1800
    }
}

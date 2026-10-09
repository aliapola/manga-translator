package com.mangatranslator.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

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

    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()
    @Volatile private var recognizersLastUsed = System.currentTimeMillis()

    private val lineBreakRegex = Regex("\\s*\n\\s*")
    private val multiSpaceRegex = Regex(" {2,}")

    private fun recognizerFor(script: OcrScript): TextRecognizer {
        recognizersLastUsed = System.currentTimeMillis()
        return recognizers.computeIfAbsent(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    /**
     * Loads the model for [script] ahead of time by running OCR on a tiny blank
     * image, so the first real tap doesn't pay the model start-up cost.
     */
    suspend fun warmUp(script: OcrScript) {
        try {
            val tiny = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            tiny.eraseColor(Color.WHITE)
            recognizerFor(script).process(InputImage.fromBitmap(tiny, 0)).await()
            tiny.recycle()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Warm-up is best-effort.
        }
    }

    /**
     * Runs OCR on [bitmap], optionally restricted to [region] (a user-selected
     * crop of the captured screen). [script] should match the source language
     * chosen by the user, since manga/manhwa/manhua use different CJK models.
     *
     * The bitmap goes to ML Kit as-is: the previous contrast-enhancement pass
     * allocated and redrew a full-screen copy of the screenshot on every
     * request, which cost time on every single translation.
     *
     * Mid-sentence line breaks inside a single block are flattened into
     * spaces before translation (a sentence with real line breaks in it gets
     * translated line-by-line by the offline model and comes out stilted).
     * Positioning is always ML Kit's own per-block bounding box, unmodified.
     *
     * Call from a background dispatcher (bitmap cropping happens on the caller's thread).
     */
    suspend fun recognize(
        bitmap: Bitmap,
        script: OcrScript,
        region: Rect? = null
    ): List<RecognizedBlock> {
        val source = region?.let {
            Bitmap.createBitmap(bitmap, it.left, it.top, it.width(), it.height())
        } ?: bitmap

        val image = InputImage.fromBitmap(source, 0)
        val result: Text = recognizerFor(script).process(image).await()

        val offsetX = region?.left ?: 0
        val offsetY = region?.top ?: 0

        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            val cleanedText = flattenLineBreaks(block.text)
            if (cleanedText.isBlank()) return@mapNotNull null
            val shifted = Rect(
                box.left + offsetX, box.top + offsetY,
                box.right + offsetX, box.bottom + offsetY
            )
            RecognizedBlock(
                text = cleanedText,
                boundingBox = shifted,
                isLikelyVertical = looksVertical(block)
            )
        }
    }

    /** Collapses real line breaks (and the extra spaces around them) into single spaces. */
    private fun flattenLineBreaks(text: String): String =
        text.replace(lineBreakRegex, " ").replace(multiSpaceRegex, " ").trim()

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

    /** Releases OCR model instances if none have been used recently, to free memory while idle. */
    fun releaseIfIdle(idleMillis: Long) {
        if (recognizers.isNotEmpty() && System.currentTimeMillis() - recognizersLastUsed > idleMillis) {
            recognizers.values.forEach { it.close() }
            recognizers.clear()
        }
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}

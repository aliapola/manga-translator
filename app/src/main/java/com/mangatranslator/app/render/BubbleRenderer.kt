package com.mangatranslator.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** رسم ترجمه (فارسی/RTL) روی کادر حباب؛ مشترک بین Overlay و وب‌ریدر. */
object BubbleRenderer {

    /** پس‌زمینه کاملاً مات (بدون شفافیت) تا متن اصلی زیر آن دیده نشود. [opacity] دیگر استفاده نمی‌شود. */
    @Suppress("UNUSED_PARAMETER")
    fun backgroundPaint(opacity: Float = 1f): Paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** کادر را با رنگ پس‌زمینه می‌پوشاند و متن ترجمه را وسط آن می‌نویسد. */
    fun drawTranslation(canvas: Canvas, box: Rect, text: String, background: Paint) {
        if (box.width() <= 0 || box.height() <= 0) return
        // کادر OCR دقیقاً دور متن است؛ کمی بزرگش می‌کنیم تا لبه‌های متن اصلی بیرون نزند.
        val pad = maxOf(6, (minOf(box.width(), box.height()) * 0.10f).toInt())
        val padded = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        canvas.drawRect(padded, background)
        drawTextInBox(canvas, text, padded)
    }

    /** متن را وسط [box] می‌کشد و اندازه فونت را تا جا شدن کم می‌کند. */
    fun drawTextInBox(canvas: Canvas, text: String, box: Rect) {
        val padding = 8
        val availableWidth = (box.width() - padding * 2).coerceAtLeast(20)
        val availableHeight = (box.height() - padding * 2).coerceAtLeast(16)

        val textPaint = TextPaint().apply {
            color = Color.BLACK
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
        canvas.translate((box.left + padding).toFloat(), box.top + padding + verticalOffset)
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
}

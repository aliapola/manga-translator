package com.mangatranslator.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View

/**
 * Transparent layer drawn on top of the frozen screenshot. Each translated
 * block is added as soon as it is ready (see [add]), so the page fills in
 * progressively instead of appearing all at once after the slowest block.
 *
 * Coordinates are expressed in the screenshot's pixel space; [onDraw] scales
 * them to this view's size exactly like the FIT_XY ImageView underneath does,
 * so text and bubble can't drift apart.
 *
 * The expensive part (fitting the font size / building the StaticLayout) is
 * done off the UI thread in [prepare]; the view itself only draws.
 */
class TranslationLayerView(
    context: Context,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    opacity: Float
) : View(context) {

    class Item(val box: Rect, val layout: StaticLayout, val textX: Float, val textY: Float)

    private val items = ArrayList<Item>()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((opacity.coerceIn(0.3f, 1f) * 255).toInt(), 255, 255, 255)
        style = Paint.Style.FILL
    }

    /** Must be called on the UI thread. */
    fun add(item: Item) {
        items += item
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || items.isEmpty()) return
        canvas.save()
        canvas.scale(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        for (item in items) {
            canvas.drawRect(item.box, backgroundPaint)
            canvas.save()
            canvas.translate(item.textX, item.textY)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    companion object {
        private const val PADDING = 8
        private const val MAX_TEXT_PX = 40
        private const val MIN_TEXT_PX = 10

        /**
         * Fits [text] into [box] (clamped to the screenshot bounds) using the
         * largest font size that fits, found by binary search (a handful of
         * layouts instead of up to ~15). Persian/RTL-aware. Thread-safe: call
         * it from a background thread. Returns null if the box is empty.
         */
        fun prepare(text: String, box: Rect, boundsWidth: Int, boundsHeight: Int): Item? {
            val safeBox = Rect(
                box.left.coerceIn(0, boundsWidth),
                box.top.coerceIn(0, boundsHeight),
                box.right.coerceIn(0, boundsWidth),
                box.bottom.coerceIn(0, boundsHeight)
            )
            if (safeBox.width() <= 0 || safeBox.height() <= 0) return null

            val availableWidth = (safeBox.width() - PADDING * 2).coerceAtLeast(20)
            val availableHeight = (safeBox.height() - PADDING * 2).coerceAtLeast(16)

            var low = MIN_TEXT_PX
            var high = MAX_TEXT_PX
            var bestLayout: StaticLayout? = null
            while (low <= high) {
                val mid = (low + high) ushr 1
                val layout = buildLayout(text, mid.toFloat(), availableWidth)
                if (layout.height <= availableHeight) {
                    bestLayout = layout
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            val layout = bestLayout ?: buildLayout(text, MIN_TEXT_PX.toFloat(), availableWidth)

            val verticalOffset = ((availableHeight - layout.height) / 2f).coerceAtLeast(0f)
            return Item(
                box = safeBox,
                layout = layout,
                textX = (safeBox.left + PADDING).toFloat(),
                textY = safeBox.top + PADDING + verticalOffset
            )
        }

        private fun buildLayout(text: String, textSizePx: Float, width: Int): StaticLayout {
            // Each layout keeps a reference to its paint, so every layout gets its own.
            val paint = TextPaint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textSize = textSizePx
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
        }
    }
}

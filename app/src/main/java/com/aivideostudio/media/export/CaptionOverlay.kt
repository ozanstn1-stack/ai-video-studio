package com.aivideostudio.media.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TextOverlay

/**
 * Draws subtitles, the hook title and user text on top of the rendered frame.
 *
 * Media3's canvas overlay is invoked once per frame with the presentation
 * timestamp, which is what makes time-accurate captions possible: the overlay
 * keeps the whole caption list and simply draws whichever entries are active at
 * that instant. Nothing is baked into the source footage.
 */
@OptIn(UnstableApi::class)
internal class CaptionOverlay(
    private val captions: List<Caption>,
    private val overlays: List<TextOverlay>,
    private val hookText: String?,
    private val style: SubtitleStyle,
    private val totalDurationMs: Long,
) : CanvasOverlay(false) {

    private val sortedCaptions = captions.sortedBy { it.startMs }
    private val sortedOverlays = overlays.sortedBy { it.startMs }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val emphasisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val backgroundRect = RectF()

    override fun configure(size: Size) {
        super.configure(size)
        val base = size.height * BASE_TEXT_SCALE
        textPaint.textSize = base * styleScale()
        outlinePaint.textSize = base * styleScale()
        outlinePaint.strokeWidth = base * styleScale() * OUTLINE_SCALE
        emphasisPaint.textSize = base * styleScale() * EMPHASIS_SCALE
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeMs = presentationTimeUs / 1_000L
        drawHook(canvas, timeMs)
        drawOverlays(canvas, timeMs)
        drawCaptions(canvas, timeMs)
    }

    private fun drawCaptions(canvas: Canvas, timeMs: Long) {
        val active = sortedCaptions.filter { timeMs >= it.startMs && timeMs < it.endMs }
        if (active.isEmpty()) return
        active.forEach { caption -> drawCaption(canvas, caption) }
    }

    private fun drawCaption(canvas: Canvas, caption: Caption) {
        val lines = wrap(caption.text, textPaint, canvas.width * MAX_TEXT_WIDTH_FRACTION, MAX_LINES)
        if (lines.isEmpty()) return

        val lineHeight = textPaint.textSize * LINE_SPACING
        val blockHeight = lineHeight * lines.size
        val baseline = canvas.height * caption.positionY - blockHeight / 2f + lineHeight * 0.8f

        if (usesBackground()) {
            val maxWidth = lines.maxOf { textPaint.measureText(it) }
            val padding = textPaint.textSize * BACKGROUND_PADDING
            backgroundRect.set(
                canvas.width / 2f - maxWidth / 2f - padding,
                baseline - textPaint.textSize - padding,
                canvas.width / 2f + maxWidth / 2f + padding,
                baseline + blockHeight - textPaint.textSize + padding,
            )
            canvas.drawRoundRect(backgroundRect, padding, padding, backgroundPaint)
        }

        lines.forEachIndexed { index, line ->
            val y = baseline + index * lineHeight
            when (style) {
                SubtitleStyle.BOLD -> {
                    canvas.drawText(line, canvas.width / 2f, y, outlinePaint)
                    canvas.drawText(line, canvas.width / 2f, y, textPaint)
                }

                SubtitleStyle.MINIMAL -> {
                    textPaint.alpha = MINIMAL_ALPHA
                    canvas.drawText(line, canvas.width / 2f, y, textPaint)
                    textPaint.alpha = 255
                }

                SubtitleStyle.DYNAMIC -> drawDynamicLine(canvas, line, y, caption)
                else -> canvas.drawText(line, canvas.width / 2f, y, textPaint)
            }
        }
    }

    /**
     * The Dynamic style colours and enlarges the words the analysis flagged as
     * important, keeping the line centred as a whole.
     */
    private fun drawDynamicLine(canvas: Canvas, line: String, y: Float, caption: Caption) {
        if (caption.emphasisWords.isEmpty()) {
            canvas.drawText(line, canvas.width / 2f, y, textPaint)
            return
        }
        val words = line.split(' ')
        val emphasised = caption.emphasisWords.map { it.uppercase() }.toSet()
        val widths = words.map { word ->
            val paint = if (words.indexOf(word).let { it } >= 0 && word.uppercase() in emphasised) {
                emphasisPaint
            } else {
                textPaint
            }
            paint.measureText(word)
        }
        val spaces = textPaint.measureText(" ") * (words.size - 1).coerceAtLeast(0)
        var cursor = canvas.width / 2f - (widths.sum() + spaces) / 2f

        words.forEachIndexed { index, word ->
            val isEmphasised = word.uppercase() in emphasised
            val paint = if (isEmphasised) emphasisPaint else textPaint
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(word, cursor, y, paint)
            cursor += widths[index] + textPaint.measureText(" ")
            paint.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawOverlays(canvas: Canvas, timeMs: Long) {
        sortedOverlays
            .filter { timeMs >= it.startMs && timeMs < it.endMs }
            .forEach { overlay ->
                if (overlay.text.isBlank()) return@forEach
                textPaint.textSize = canvas.height * if (overlay.isTitle) TITLE_TEXT_SCALE else BASE_TEXT_SCALE
                val lines = wrap(overlay.text.uppercase(), textPaint, canvas.width * MAX_TEXT_WIDTH_FRACTION, MAX_LINES)
                val lineHeight = textPaint.textSize * LINE_SPACING
                var y = canvas.height * overlay.positionY
                lines.forEach { line ->
                    canvas.drawText(line, canvas.width * overlay.positionX, y, outlinePaint)
                    canvas.drawText(line, canvas.width * overlay.positionX, y, textPaint)
                    y += lineHeight
                }
            }
    }

    private fun drawHook(canvas: Canvas, timeMs: Long) {
        val hook = hookText?.takeIf { it.isNotBlank() } ?: return
        if (timeMs >= HOOK_DURATION_MS || timeMs < 0) return
        val previousSize = textPaint.textSize
        textPaint.textSize = canvas.height * HOOK_TEXT_SCALE
        val alpha = if (timeMs > HOOK_DURATION_MS - HOOK_FADE_MS) {
            ((HOOK_DURATION_MS - timeMs).toFloat() / HOOK_FADE_MS * 255).toInt().coerceIn(0, 255)
        } else {
            255
        }
        textPaint.alpha = alpha
        val lines = wrap(hook.uppercase(), textPaint, canvas.width * MAX_TEXT_WIDTH_FRACTION, MAX_LINES)
        var y = canvas.height * HOOK_POSITION_Y
        val lineHeight = textPaint.textSize * LINE_SPACING
        lines.forEach { line ->
            canvas.drawText(line, canvas.width / 2f, y, textPaint)
            y += lineHeight
        }
        textPaint.alpha = 255
        textPaint.textSize = previousSize
    }

    /**
     * Greedy word wrap. Captions are already short, so a simple measure-and-place
     * loop produces the same result as a full text layout with far less code.
     */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current.clear()
                current.append(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
                if (lines.size == maxLines) return@forEach
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current.toString()

        return if (lines.size > maxLines) lines.take(maxLines) else lines
    }

    private fun styleScale(): Float = when (style) {
        SubtitleStyle.MINIMAL -> 0.82f
        SubtitleStyle.BOLD -> 1.10f
        SubtitleStyle.CREATOR -> 1.0f
        SubtitleStyle.CLEAN -> 0.96f
        SubtitleStyle.DYNAMIC -> 1.06f
    }

    private fun usesBackground(): Boolean = style == SubtitleStyle.CREATOR ||
        style == SubtitleStyle.CLEAN

    fun totalDuration(): Long = totalDurationMs

    private companion object {
        const val BASE_TEXT_SCALE = 0.048f
        const val TITLE_TEXT_SCALE = 0.062f
        const val HOOK_TEXT_SCALE = 0.052f
        const val EMPHASIS_SCALE = 1.16f
        const val OUTLINE_SCALE = 0.09f
        const val BACKGROUND_PADDING = 0.30f
        const val LINE_SPACING = 1.22f
        const val MAX_TEXT_WIDTH_FRACTION = 0.86f
        const val MAX_LINES = 2
        const val MINIMAL_ALPHA = 220
        const val HOOK_DURATION_MS = 3_200L
        const val HOOK_FADE_MS = 500L
        const val HOOK_POSITION_Y = 0.14f
        val ACCENT_COLOR = Color.parseColor("#A78BFA")
    }
}

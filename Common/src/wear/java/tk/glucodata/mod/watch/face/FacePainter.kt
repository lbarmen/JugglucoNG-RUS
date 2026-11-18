package tk.glucodata.mod.watch.face

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import tk.glucodata.TrendArrowAngle

/**
 * JugglucoNGmod, watch block (part 3b): paints a [FaceModel] on a canvas by [FaceLayout] and [FaceStyle]. No data
 * reading here: the renderer hands over the model; [traceColor] gives a graph point its range colour.
 *
 * Every text is fitted into its row by its measured bounds and set on its baseline (critic 3a, N8: a text's real
 * height is not its size), so nothing reaches the round edge. One painter per renderer, used on its thread.
 */
internal class FacePainter {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bounds = Rect()
    private val path = Path()
    private val box = RectF()

    /**
     * Paints the face on [canvas] ([widthPx] x [heightPx]) and returns the layout used (the tap area). With
     * [showData] false (no snapshot yet) only the time and the date are drawn; [trial] adds the "TEST" mark.
     */
    fun draw(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        model: FaceModel,
        isMmol: Boolean,
        ambient: Boolean,
        showData: Boolean,
        trial: Boolean,
        labels: FaceLabels,
        traceColor: (Float) -> Int,
    ): FaceLayout.Layout {
        val style = FaceStyle.of(ambient)
        val layout = FaceLayout.compute(widthPx, heightPx)
        val d = layout.radius * 2f
        canvas.drawColor(style.background)
        drawClock(canvas, layout.time, model.clock, style)
        drawCentred(canvas, model.dateText, layout.date, layout.date.textSize, style.secondary, bold = false)
        if (showData) {
            drawValueRow(canvas, layout, model, style)
            drawGraph(canvas, layout, d, model, isMmol, style, traceColor)
            val batteries = FaceText.batteries(model.phoneBatteryText, model.watchBatteryText, labels)
            drawCentred(canvas, batteries, layout.battery, layout.battery.textSize, style.secondary, bold = false)
        }
        if (trial) drawCentred(canvas, TRIAL_MARK, layout.mark, layout.mark.textSize, style.mark, bold = true)
        return layout
    }

    // ------------------------------------------------------------------ texts

    private fun drawClock(canvas: Canvas, row: FaceLayout.Row, clock: FaceText.Clock, style: FaceStyle) {
        val part = clock.dayPart
        if (part == null) {
            drawCentred(canvas, clock.text, row, row.textSize, style.time, bold = false)
            return
        }
        // 12-hour mode: "2:35" with a small "pm" after it, both centred together
        val size = fitSize(clock.text + " " + part, row, row.textSize, bold = false)
        setText(size, style.time, bold = false)
        val timeWidth = text.measureText(clock.text)
        text.getTextBounds(clock.text, 0, clock.text.length, bounds)
        val baseline = row.top + row.height / 2f - bounds.exactCenterY()
        val partSize = size * 0.4f
        text.textSize = partSize
        val partWidth = text.measureText(part)
        val gap = size * 0.12f
        val left = row.centerX - (timeWidth + gap + partWidth) / 2f
        text.textSize = size
        canvas.drawText(clock.text, left, baseline, text)
        text.textSize = partSize
        canvas.drawText(part, left + timeWidth + gap, baseline - size * 0.35f, text)
    }

    /** [s] centred in [row], at most [maxSize], shrunk to the row's width and height. */
    private fun drawCentred(canvas: Canvas, s: String, row: FaceLayout.Row, maxSize: Float, color: Int, bold: Boolean) {
        if (s.isEmpty() || row.width <= 0f || row.height <= 0f) return
        setText(fitSize(s, row, maxSize, bold), color, bold)
        text.getTextBounds(s, 0, s.length, bounds)
        val width = text.measureText(s)
        canvas.drawText(s, row.centerX - width / 2f, row.top + row.height / 2f - bounds.exactCenterY(), text)
    }

    /** The largest size up to [maxSize] at which [s] fits [row] by its measured width and glyph height. */
    private fun fitSize(s: String, row: FaceLayout.Row, maxSize: Float, bold: Boolean): Float {
        setText(maxSize, 0, bold)
        var size = maxSize
        val width = text.measureText(s)
        if (width > row.width && width > 0f) size *= row.width / width
        text.textSize = size
        text.getTextBounds(s, 0, s.length, bounds)
        if (bounds.height() > row.height && bounds.height() > 0) size *= row.height / bounds.height()
        return size
    }

    private fun setText(size: Float, color: Int, bold: Boolean) {
        text.textSize = size.coerceAtLeast(1f)
        text.color = color
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ------------------------------------------------------------------ value row

    private fun drawValueRow(canvas: Canvas, layout: FaceLayout.Layout, model: FaceModel, style: FaceStyle) {
        val row = layout.value
        val valueColor = if (model.hasValue) style.valueColor(model.valueColor) else style.neutral
        setText(row.textSize, valueColor, bold = true)
        val valueWidth = text.measureText(model.valueText)
        val arrowWidth = if (model.rate.isFinite()) layout.arrowSize else 0f
        setText(layout.sideTextSize, style.secondary, bold = false)
        val delta = model.deltaText
        val age = model.ageText
        val sideWidth = maxOf(delta?.let { text.measureText(it) } ?: 0f, age?.let { text.measureText(it) } ?: 0f)
        val place = FaceLayout.placeValueRow(row, valueWidth, arrowWidth, sideWidth, layout.radius * 0.04f)
        val centerY = row.top + row.height / 2f

        // the number, its digits centred on the row
        setText(row.textSize * place.scale, valueColor, bold = true)
        text.getTextBounds(model.valueText, 0, model.valueText.length, bounds)
        canvas.drawText(model.valueText, place.valueX, centerY - bounds.exactCenterY(), text)

        if (arrowWidth > 0f) {
            val size = layout.arrowSize * place.scale
            drawArrow(canvas, place.arrowX, centerY - size / 2f, size, model.rate, valueColor, style.arrowStroke)
        }

        // change over age, one above the other beside the number
        val sideSize = layout.sideTextSize * place.scale
        setText(sideSize, style.secondary, bold = false)
        text.getTextBounds("0", 0, 1, bounds)
        val capHeight = bounds.height().toFloat()
        val lineGap = capHeight * 0.6f
        val ageColor = if (model.ageAlarm) style.ageAlarm else style.age
        if (delta != null && age != null) {
            val upper = centerY - lineGap / 2f
            canvas.drawText(delta, place.sideX, upper, text)
            text.color = ageColor
            canvas.drawText(age, place.sideX, upper + lineGap + capHeight, text)
        } else if (delta != null || age != null) {
            text.color = if (age != null) ageColor else style.secondary
            canvas.drawText(age ?: delta!!, place.sideX, centerY + capHeight / 2f, text)
        }
    }

    /**
     * Upstream's trend arrow (`ComplicationRenderer.drawArrow`: angle from `TrendArrowAngle`, a double head past
     * 2 mg/dL/min, the same proportions) with our stroke weight, thinner in ambient.
     */
    private fun drawArrow(canvas: Canvas, left: Float, top: Float, size: Float, rate: Float, color: Int, weight: Float) {
        val cx = left + size / 2f
        val cy = top + size / 2f
        val speed = abs(rate)
        val double = speed > 2f
        val scale = 1f + (speed * 0.12f).coerceAtMost(0.5f)
        val headSpan = size * 0.55f
        val headDepth = headSpan / 2f
        val gap = headDepth * 0.5f
        val length = (size * (if (double) 0.35f else 0.6f) * scale).coerceAtMost(size * (if (double) 0.42f else 0.72f))
        val total = if (double) length + gap + headDepth else length
        stroke.pathEffect = null
        stroke.strokeWidth = size * weight
        stroke.color = color
        canvas.save()
        canvas.rotate(TrendArrowAngle.rotationDegrees(rate), cx, cy)
        val start = cx - total / 2f
        val tip = start + length
        path.reset()
        path.moveTo(start, cy)
        path.lineTo(tip, cy)
        path.moveTo(tip - headDepth, cy - headSpan / 2f)
        path.lineTo(tip, cy)
        path.lineTo(tip - headDepth, cy + headSpan / 2f)
        if (double) {
            val second = tip + gap + headDepth
            path.moveTo(second - headDepth, cy - headSpan / 2f)
            path.lineTo(second, cy)
            path.lineTo(second - headDepth, cy + headSpan / 2f)
        }
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    // ------------------------------------------------------------------ graph

    private fun drawGraph(
        canvas: Canvas,
        layout: FaceLayout.Layout,
        diameter: Float,
        model: FaceModel,
        isMmol: Boolean,
        style: FaceStyle,
        traceColor: (Float) -> Int,
    ) {
        val row = layout.graph
        val labelSize = layout.graphLabelSize
        val plot = model.graph
        if (row.width <= 0f || row.height <= 0f) return
        val locale = Locale.getDefault()
        val highText = FaceStyle.rangeLabel(plot.high, isMmol, locale)
        val lowText = FaceStyle.rangeLabel(plot.low, isMmol, locale)
        setText(labelSize, style.secondary, bold = false)
        val labelGap = diameter * 0.012f
        val labelWidth = maxOf(text.measureText(highText), text.measureText(lowText))
        val plotLeft = row.left
        val plotRight = (row.right - labelWidth - labelGap).coerceAtLeast(plotLeft + 1f)
        val plotWidth = plotRight - plotLeft
        fun x(timeMs: Long) = plotLeft + FaceGraph.xFraction(plot, timeMs) * plotWidth
        fun y(value: Float) = row.top + FaceGraph.yFraction(plot, value) * row.height
        val yHigh = y(plot.high)
        val yLow = y(plot.low)

        style.rangeFill?.let {
            fill.color = it
            canvas.drawRect(plotLeft, minOf(yHigh, yLow), plotRight, maxOf(yHigh, yLow), fill)
        }
        style.frame?.let {
            stroke.pathEffect = null
            stroke.strokeWidth = (diameter * style.frameWidth).coerceAtLeast(1f)
            stroke.color = it
            box.set(plotLeft, row.top, plotRight, row.bottom)
            canvas.drawRoundRect(box, diameter * 0.01f, diameter * 0.01f, stroke)
        }

        // thin lines at full hours of local time, their hours under the graph (not in the dim mode)
        val hourLines = FaceLayout.hourLines(plot, model.hourMarks, plotLeft, plotRight)
        stroke.pathEffect = null
        stroke.strokeWidth = (diameter * style.hourLineWidth).coerceAtLeast(1f)
        stroke.color = style.hourLine
        for (line in hourLines) canvas.drawLine(line.x, row.top, line.x, row.bottom, stroke)
        style.hourLabel?.let { color -> drawHourLabels(canvas, layout.hours, hourLines, color, diameter) }

        // target lines, dashed, their values at the right end
        stroke.strokeWidth = (diameter * style.rangeLineWidth).coerceAtLeast(1f)
        stroke.color = style.rangeLine
        val dash = diameter * 0.012f
        stroke.pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        canvas.drawLine(plotLeft, yHigh, plotRight, yHigh, stroke)
        canvas.drawLine(plotLeft, yLow, plotRight, yLow, stroke)
        stroke.pathEffect = null
        setText(labelSize, style.secondary, bold = false)
        text.getTextBounds("0", 0, 1, bounds)
        val half = bounds.height() / 2f
        val labelX = plotRight + labelGap
        canvas.drawText(highText, labelX, (yHigh + half).coerceIn(row.top + 2 * half, row.bottom), text)
        canvas.drawText(lowText, labelX, (yLow + half).coerceIn(row.top + 2 * half, row.bottom), text)

        // the trace: a step crossing a target line is cut there (part 3c2), so what lies above the high line or below
        // the low one has the colour the number would have out of range - always, even with upstream's range colours
        // off (part 5b, [FaceGraph.traceColor] in [traceColor]) - the rest as before; gaps stay open
        stroke.strokeWidth = (diameter * style.traceWidth).coerceAtLeast(1f)
        for (segment in FaceGraph.segments(plot.points, FaceGraph.MAX_GAP_MS)) {
            if (segment.size == 1) {
                val p = segment[0]
                fill.color = style.traceColor(traceColor(p.value))
                canvas.drawCircle(x(p.timeMs), y(p.value), stroke.strokeWidth / 2f, fill)
                continue
            }
            for (i in 1 until segment.size) {
                for (piece in FaceGraph.splitByRange(segment[i - 1], segment[i], plot.low, plot.high)) {
                    stroke.color = style.traceColor(traceColor(piece.colorValue))
                    canvas.drawLine(x(piece.from.timeMs), y(piece.from.value), x(piece.to.timeMs), y(piece.to.value),
                        stroke)
                }
            }
        }
    }

    /** The hours under their lines, small, centred; a label without room is left out ([FaceLayout.placeHourLabels]). */
    private fun drawHourLabels(
        canvas: Canvas,
        row: FaceLayout.Row,
        lines: List<FaceLayout.HourLine>,
        color: Int,
        diameter: Float,
    ) {
        if (lines.isEmpty() || row.width <= 0f || row.height <= 0f) return
        setText(fitSize("00", row, row.textSize, bold = false), color, bold = false)
        val widths = lines.map { text.measureText(it.label) }
        text.getTextBounds("0", 0, 1, bounds)
        val baseline = row.top + row.height / 2f - bounds.exactCenterY()
        FaceLayout.placeHourLabels(row, lines, widths, diameter * 0.01f).forEachIndexed { i, left ->
            if (left != null) canvas.drawText(lines[i].label, left, baseline, text)
        }
    }

    companion object {
        /** Marks a local trial build with made-up values; never shown with the real source. */
        const val TRIAL_MARK = "TEST"
    }
}

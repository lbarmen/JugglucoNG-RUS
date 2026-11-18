package tk.glucodata.mod.watch.face

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JugglucoNGmod, watch block (part 3a): where the face's rows sit on a round screen, without a Canvas (the sketch in
 * the watch plan, "Watch face"):
 * ```
 *            14:35            time
 *          Tue 17 Nov         date
 *      6.4 ↗   +0.3           value, arrow, change over age
 *              2 min
 *   ┌────────────────────┐
 *   │ - - - - - - - 10.0 │    graph with the target range lines
 *   │ - - - - - - -  3.9 │    (thin lines at full hours, part 3c2)
 *   └────────────────────┘
 *       8     9     10        hour labels under the lines
 *     phone 64%  watch 81%    batteries
 * ```
 * Every share is of the screen's diameter (the smaller side, centred); a row is as wide as the circle allows at its
 * edge farther from the centre, less a margin, so nothing reaches the round edge on any size.
 */
object FaceLayout {

    /** Top and bottom of each row, shares of the diameter from the top. */
    private const val TIME_TOP = 0.10f
    private const val TIME_BOTTOM = 0.22f
    private const val DATE_TOP = 0.235f
    private const val DATE_BOTTOM = 0.295f
    private const val VALUE_TOP = 0.32f
    private const val VALUE_BOTTOM = 0.47f
    private const val GRAPH_TOP = 0.50f
    private const val GRAPH_BOTTOM = 0.70f
    /** The hour labels under the graph's hour lines (part 3c2), above the batteries. */
    private const val HOURS_TOP = 0.705f
    private const val HOURS_BOTTOM = 0.745f
    private const val BATTERY_TOP = 0.76f
    private const val BATTERY_BOTTOM = 0.81f
    /** The "TEST" mark of a local trial build (part 3b), under the batteries. */
    private const val MARK_TOP = 0.83f
    private const val MARK_BOTTOM = 0.875f

    /** Text sizes, shares of the diameter. */
    private const val TIME_TEXT = 0.12f
    private const val DATE_TEXT = 0.06f
    private const val VALUE_TEXT = 0.15f
    private const val ARROW = 0.09f
    private const val SIDE_TEXT = 0.065f
    private const val GRAPH_LABEL_TEXT = 0.045f
    private const val HOURS_TEXT = 0.035f
    private const val BATTERY_TEXT = 0.045f
    private const val MARK_TEXT = 0.04f

    /** The tap area of the value reaches this share of the value row's height above and below it. */
    private const val TAP_SLACK = 0.25f

    /** Space kept from the round edge on each side, share of the diameter. */
    const val MARGIN = 0.04f

    /**
     * A generous average glyph width, share of the text size, for checking that a text fits without measuring it
     * (digits and letters of the face's fonts are narrower); the renderer measures for real.
     */
    const val CHAR_WIDTH = 0.6f

    /** One row in pixels: its box and its text size. */
    data class Row(val left: Float, val top: Float, val right: Float, val bottom: Float, val textSize: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
    }

    /**
     * The face on a [widthPx] x [heightPx] screen: the rows top to bottom; in the value row the number takes
     * [Row.textSize], the arrow [arrowSize] and the change and age, one over the other, [sideTextSize].
     */
    data class Layout(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val time: Row,
        val date: Row,
        val value: Row,
        val arrowSize: Float,
        val sideTextSize: Float,
        val graph: Row,
        val graphLabelSize: Float,
        /** The labels of the graph's hour lines (part 3c2). */
        val hours: Row,
        val battery: Row,
        val mark: Row,
    ) {
        val rows: List<Row> get() = listOf(time, date, value, graph, hours, battery, mark)
    }

    /** A thin hour line of the graph at [x] (pixels) and its [label]. */
    data class HourLine(val x: Float, val label: String)

    /**
     * Where the value row's parts start (left edges, pixels) once the whole is centred in the row, and the [scale]
     * every part's size is multiplied by when the whole would be wider than the row (1 when it fits).
     */
    data class ValuePlacement(val scale: Float, val valueX: Float, val arrowX: Float, val sideX: Float)

    fun compute(widthPx: Int, heightPx: Int): Layout {
        val size = min(widthPx, heightPx).toFloat().coerceAtLeast(1f)
        val left = (widthPx - size) / 2f
        val top = (heightPx - size) / 2f
        fun row(rowTop: Float, rowBottom: Float, text: Float): Row {
            // The edge farther from the centre is the narrower one.
            val far = maxOf(abs(rowTop - 0.5f), abs(rowBottom - 0.5f))
            val half = (sqrt((0.25f - far * far).coerceAtLeast(0f)) - MARGIN).coerceAtLeast(0f)
            return Row(left + (0.5f - half) * size, top + rowTop * size, left + (0.5f + half) * size,
                top + rowBottom * size, text * size)
        }
        return Layout(
            centerX = left + size / 2f,
            centerY = top + size / 2f,
            radius = size / 2f,
            time = row(TIME_TOP, TIME_BOTTOM, TIME_TEXT),
            date = row(DATE_TOP, DATE_BOTTOM, DATE_TEXT),
            value = row(VALUE_TOP, VALUE_BOTTOM, VALUE_TEXT),
            arrowSize = ARROW * size,
            sideTextSize = SIDE_TEXT * size,
            graph = row(GRAPH_TOP, GRAPH_BOTTOM, GRAPH_LABEL_TEXT),
            graphLabelSize = GRAPH_LABEL_TEXT * size,
            hours = row(HOURS_TOP, HOURS_BOTTOM, HOURS_TEXT),
            battery = row(BATTERY_TOP, BATTERY_BOTTOM, BATTERY_TEXT),
            mark = row(MARK_TOP, MARK_BOTTOM, MARK_TEXT),
        )
    }

    /**
     * The value row of measured widths: the number, the arrow (0 - none) and the wider of change and age (0 - none),
     * [gap] between present parts; centred, scaled down together when wider than the row.
     */
    fun placeValueRow(row: Row, valueWidth: Float, arrowWidth: Float, sideWidth: Float, gap: Float): ValuePlacement {
        val gaps = (if (arrowWidth > 0f) gap else 0f) + (if (sideWidth > 0f) gap else 0f)
        val total = valueWidth + arrowWidth + sideWidth + gaps
        val scale = if (total > row.width && total > 0f) (row.width / total).coerceAtLeast(0f) else 1f
        val left = row.centerX - total * scale / 2f
        val arrowX = left + (valueWidth + (if (arrowWidth > 0f) gap else 0f)) * scale
        val sideX = arrowX + (arrowWidth + (if (sideWidth > 0f) gap else 0f)) * scale
        return ValuePlacement(scale, left, arrowX, sideX)
    }

    /**
     * The graph's hour lines: each of [marks] at its time in the [plot]'s window, between [plotLeft] and [plotRight]
     * (pixels; the plot box, left of the range labels).
     */
    fun hourLines(plot: FaceGraph.Plot, marks: List<FaceGraph.HourMark>, plotLeft: Float, plotRight: Float):
        List<HourLine> = marks.map { HourLine(plotLeft + FaceGraph.xFraction(plot, it.timeMs) * (plotRight - plotLeft), it.label) }

    /**
     * Left edges of the hour labels in the [hours] row, each centred under its line ([widths] - measured, same
     * order as [lines]); `null` - the label is left out (its line stays): it would reach past the row (the round
     * edge) or come closer than [gap] to the label before it. The row lies between the graph (with the range labels
     * at its right) and the batteries, so a label never touches either.
     */
    fun placeHourLabels(hours: Row, lines: List<HourLine>, widths: List<Float>, gap: Float): List<Float?> {
        var lastRight = Float.NEGATIVE_INFINITY
        return lines.mapIndexed { i, line ->
            val width = widths.getOrElse(i) { 0f }
            val left = line.x - width / 2f
            val right = left + width
            if (width <= 0f || left < hours.left || right > hours.right || left < lastRight + gap) null
            else {
                lastRight = right
                left
            }
        }
    }

    /** A tap at [x], [y] (pixels) is on the value: the value row, a little taller, across its width. */
    fun isOnValue(layout: Layout, x: Float, y: Float): Boolean {
        val row = layout.value
        val slack = row.height * TAP_SLACK
        return x >= row.left && x <= row.right && y >= row.top - slack && y <= row.bottom + slack
    }

    /** A width estimate for [text] at [textSize] (see [CHAR_WIDTH]). */
    fun estimatedWidth(text: String, textSize: Float): Float = text.length * CHAR_WIDTH * textSize

    /**
     * The value row's content width estimate: the number, the arrow and the wider of change and age, with the
     * gaps the renderer leaves between them.
     */
    fun valueRowWidth(layout: Layout, valueText: String, deltaText: String, ageText: String): Float {
        val gap = layout.radius * 0.04f
        return estimatedWidth(valueText, layout.value.textSize) + gap + layout.arrowSize + gap +
            maxOf(estimatedWidth(deltaText, layout.sideTextSize), estimatedWidth(ageText, layout.sideTextSize))
    }
}
